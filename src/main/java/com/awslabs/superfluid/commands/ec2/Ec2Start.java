package com.awslabs.superfluid.commands.ec2;

import com.awslabs.superfluid.App;
import com.awslabs.superfluid.data.AMI;
import com.awslabs.superfluid.helpers.IamHelper;
import com.awslabs.superfluid.helpers.SshHelper;
import com.awslabs.superfluid.helpers.SystemHelper;
import com.awslabs.superfluid.visual.Spinner;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.collection.List;
import io.vavr.collection.Traversable;
import io.vavr.control.Option;
import io.vavr.control.Try;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import picocli.CommandLine;
import software.amazon.awssdk.services.ec2.model.*;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import static com.awslabs.superfluid.helpers.Shared.*;
import static com.awslabs.superfluid.helpers.SshHelper.runCommand;
import static java.text.MessageFormat.format;

@CommandLine.Command(name = "start", mixinStandardHelpOptions = true)
public class Ec2Start implements Runnable {
    public static final String SECURITY_GROUP_NAME = String.join("-", App.TOOL_NAME, "security-group");
    public static final String VPC_NAME = String.join("-", App.TOOL_NAME, "vpc");
    public static final String VPC_NAME_TAG = "Name";
    public static final String VPC_ID = "vpc-id";
    public static final String ANY_IP_CIDR = "0.0.0.0/0";
    public static final String TCP = "tcp";
    public static final String UDP = "udp";
    public static final int MOSH_UDP_START_PORT = 60000;
    public static final int MOSH_UDP_END_PORT = 61000;
    public static final int SSH_TCP_PORT = 22;
    public static final InstanceType DEFAULT_INSTANCE_TYPE = InstanceType.T3_2_XLARGE;
    public static final String INSTANCE_TYPE_FILTER_NAME = "instance-type";
    public static final String MUTAGEN = "mutagen";
    public static final String SUBNET_CIDR_BLOCK = "10.0.0.0/24";
    public static final String VPC_CIDR_BLOCK = "10.0.0.0/16";
    public static final AttributeBooleanValue TRUE_ATTRIBUTE = AttributeBooleanValue.builder().value(true).build();
    public static final String GROUP_NAME = "group-name";
    private static final AMI UBUNTU_20_04_LTS = AMI.builder().id("ami-00fa576fb10a52a1c").initialUser("ubuntu").build();
    private static final AMI DEFAULT_AMI = UBUNTU_20_04_LTS;
    private static final Spinner SPINNER = Spinner.Standard();
    @CommandLine.Option(names = {"-s", "--sync"}, description = "Directory to sync with remote host with mutagen", paramLabel = "syncDir")
    private Optional<String> syncDirectoryOptional;
    @CommandLine.Option(names = {"-k", "--key-pair"}, description = "The name of the key pair to use", paramLabel = "keyPairName")
    private Optional<String> keyPairNameOptional;
    @CommandLine.Option(names = {"-r", "--role"}, description = "The IAM role name (not ARN) to attach to the instance", paramLabel = "roleName")
    private Optional<String> roleNameOptional;
    @CommandLine.Option(names = {"--connect"}, description = "Connect via ssh in a new window when the instance is ready", paramLabel = "connect", arity = "0")
    private boolean connect;
    @CommandLine.Option(names = {"--no-aws-cli"}, description = "Install the AWS CLI v2 on the instance after it launches (true by default)", paramLabel = "awsCliV2", arity = "0", negatable = true)
    private boolean awsCliV2 = true;

    @Override
    public void run() {
        if (syncDirectoryOptional.isPresent()) {
            log().error("Sync directory is not supported yet");
            System.exit(1);
        }

        IamInstanceProfileSpecification.Builder iamInstanceProfileSpecificationBuilder = IamInstanceProfileSpecification.builder();

        if (roleNameOptional.isPresent()) {
            String roleName = roleNameOptional.get();

            if (!IamHelper.roleExists(roleName)) {
                log().error("Role does not exist: " + roleName);
                System.exit(1);
            }

            iamInstanceProfileSpecificationBuilder.name(roleName);
        }

        IamInstanceProfileSpecification iamInstanceProfileSpecification = iamInstanceProfileSpecificationBuilder.build();

        AMI ami = DEFAULT_AMI;
        InstanceType instanceType = DEFAULT_INSTANCE_TYPE;

        // Get our key pairs and rethrow exceptions
        List<KeyPairInfo> keyPairInfoList = SshHelper.getKeyPairs().get();

        String sshKeyName;

        if (keyPairNameOptional.isEmpty()) {
            List<KeyPairInfo> distinctFingerprints = keyPairInfoList.distinctBy(KeyPairInfo::keyFingerprint);

            if (distinctFingerprints.size() != 1) {
                log().error("More than one key pair fingerprint found, please specify the key pair name");
                printKeyPairNames(keyPairInfoList);
                System.exit(1);
            }

            sshKeyName = distinctFingerprints.get().keyName();
        } else {
            sshKeyName = keyPairNameOptional.get();
        }

        Option<KeyPairInfo> keyPairInfoOption = keyPairInfoList.find(keyPairInfo -> keyPairInfo.keyName().equals(sshKeyName));

        if (keyPairInfoOption.isEmpty()) {
            log().error("Key pair with name [{}] was not found", sshKeyName);
            printKeyPairNames(keyPairInfoList);
            System.exit(1);
        }

        // Validated the key pair name, try to load JSch. We do this now so we can fail early if the keys don't load.
        //   Otherwise we can end up with an EC2 instance launched that gets lost.
        JSch jSch = SshHelper.getJschWithPrivateKeysLoaded();

        log().debug("Using AMI: {}", ami.id());
        log().debug("Using instance type: {}", instanceType);
        log().debug("Using SSH key name: {}", sshKeyName);

        if (syncDirectoryOptional.isPresent() && SystemHelper.toolMissing(MUTAGEN)) {
            log().error("Syncing was requested but mutagen is not installed. Please install mutagen and try again or launch the instance without the sync option.");
            System.exit(1);
        }

        // Does our special VPC exist?
        if (!namedVpcExists(VPC_NAME)) {
            // It does not exist, create it
            log().debug("VPC does not exist, creating it");
            Vpc vpc = createAndTagVpcWithName(VPC_NAME);
            log().debug("VPC created with id: {} and name: {}", vpc.vpcId(), VPC_NAME);
        }

        Vpc vpc = getVpcByName(VPC_NAME).get();

        Subnet subnet = Option.of(getSubnetsInVpc(vpc))
                // Get the first subnet (there should be only one)
                .flatMap(Traversable::headOption)
                // If the list is empty then we need to create a subnet
                .getOrElse(() -> createSubnetInVpc(vpc));

        if (!securityGroupExists(SECURITY_GROUP_NAME)) {
            // It does not exist, create it
            String securityGroupId = createSecurityGroup(vpc.vpcId(), SECURITY_GROUP_NAME);
            authorizeSecurityGroupIngress(securityGroupId);
        }

        SecurityGroup securityGroup = getSecurityGroupByName(SECURITY_GROUP_NAME);

        RunInstancesResponse runInstancesResponse = runInstance(ami, instanceType.toString(), sshKeyName, securityGroup, subnet, iamInstanceProfileSpecification);

        DescribeInstancesRequest describeInstancesRequest = DescribeInstancesRequest.builder()
                .instanceIds(runInstancesResponse.instances().get(0).instanceId())
                .build();

        SPINNER.start("Waiting for instance to start");

        Instance instance = Failsafe.with(nullFailsafePolicyWithoutStatus(Instance.class, 10))
                .get(() -> ec2Client().describeInstances(describeInstancesRequest)
                        .reservations()
                        .get(0)
                        .instances()
                        .stream()
                        .filter(x -> x.publicIpAddress() != null)
                        .findFirst()
                        .get());


        String publicIpAddress = instance.publicIpAddress();
        String instanceId = instance.instanceId();

        SPINNER.success(format("Instance running at {0} with id {1}", publicIpAddress, instanceId));

        SPINNER.start("Waiting for instance to be reachable");

        Session session = SshHelper.getSessionWithoutStatus(Option.of(jSch), publicIpAddress, ami.initialUser(), false);

        SPINNER.success("Instance is reachable");

        if (awsCliV2) {
            SPINNER.start("Installing AWS CLI v2");

            // Wait for cloud-init to finish
            Try.run(() -> runCommand(session, "while [ ! -f /var/lib/cloud/instance/boot-finished ] ; do sleep 1; done"))
                    .andThenTry(() -> runCommand(session, "sudo apt update"))
                    .andThenTry(() -> runCommand(session, "sudo apt install -y unzip"))
                    .andThenTry(() -> runCommand(session, "curl -O https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip"))
                    .andThenTry(() -> runCommand(session, "unzip awscli-exe-linux-x86_64.zip"))
                    .andThenTry(() -> runCommand(session, "sudo ./aws/install"))
                    // Clean up the ZIP file and the temp directory where we extracted it
                    .andThenTry(() -> runCommand(session, "rm awscli-exe-linux-x86_64.zip"))
                    .andThenTry(() -> runCommand(session, "rm -rf aws"))
                    .onSuccess(v -> SPINNER.success("AWS CLI v2 installed"))
                    .onFailure(Exception.class, e -> SPINNER.fail("AWS CLI v2 installation failed"))
                    .get();
        }

        session.disconnect();

        println();
        println("You can connect to the instance with the following command:");

        String sshCommand = "ssh " + ami.initialUser() + "@" + publicIpAddress;

        println(sshCommand);

        if (connect) {
            println("Connecting to instance in a new terminal...");
            SystemHelper.runCommandInTerminal(sshCommand, Option.of("ssh-to-" + instanceId + ".sh"));
        }
    }

    private void printKeyPairNames(List<KeyPairInfo> keyPairInfoList) {
        log().error("The following key pair names are valid:");
        keyPairInfoList.map(KeyPairInfo::keyName).forEach(name -> log().error(" - {}", name));
    }

    private String validAzForInstanceType() {
        Filter instanceTypeFilter = Filter.builder().name(INSTANCE_TYPE_FILTER_NAME)
                .values(DEFAULT_INSTANCE_TYPE.toString())
                .build();

        DescribeInstanceTypeOfferingsRequest describeInstanceTypeOfferingsRequest = DescribeInstanceTypeOfferingsRequest.builder()
                .locationType(LocationType.AVAILABILITY_ZONE)
                .filters(instanceTypeFilter)
                .build();

        return Option.of(ec2Client().describeInstanceTypeOfferings(describeInstanceTypeOfferingsRequest))
                .map(DescribeInstanceTypeOfferingsResponse::instanceTypeOfferings)
                .map(List::ofAll)
                .flatMap(List::headOption)
                .get()
                .location();
    }

    private void deleteVpc(Vpc vpc) {
        deleteSubnets(getSubnetsInVpc(vpc));
        deleteSecurityGroup(getSecurityGroupByName(SECURITY_GROUP_NAME));

        DeleteVpcRequest deleteVpcRequest = DeleteVpcRequest.builder()
                .vpcId(vpc.vpcId())
                .build();

        ec2Client().deleteVpc(deleteVpcRequest);
    }

    private void deleteSecurityGroup(SecurityGroup securityGroup) {
        DeleteSecurityGroupRequest deleteSecurityGroupRequest = DeleteSecurityGroupRequest.builder()
                .groupId(securityGroup.groupId())
                .build();

        ec2Client().deleteSecurityGroup(deleteSecurityGroupRequest);
    }

    private void deleteSubnets(List<Subnet> subnets) {
        subnets.map(subnet -> DeleteSubnetRequest.builder()
                        .subnetId(subnet.subnetId())
                        .build())
                .forEach(deleteSubnetRequest -> ec2Client().deleteSubnet(deleteSubnetRequest));
    }

    private Subnet createSubnetInVpc(Vpc vpc) {
        CreateSubnetRequest createSubnetRequest = CreateSubnetRequest.builder()
                .vpcId(vpc.vpcId())
                .cidrBlock(SUBNET_CIDR_BLOCK)
                .availabilityZone(validAzForInstanceType())
                .build();

        log().debug("Creating subnet with CIDR block {} in AZ {}", createSubnetRequest.cidrBlock(), createSubnetRequest.availabilityZone());

        return Option.of(ec2Client().createSubnet(createSubnetRequest))
                .map(CreateSubnetResponse::subnet)
                .map(subnet -> Tuple.of(subnet, mapPublicIpOnLaunch(subnet)))
                .map(Tuple2::_1)
                .flatMap(this::describeSubnet)
                .map(subnet -> Tuple.of(subnet, attachInternetGatewayToVpc(vpc)))
                .map(tuple -> Tuple.of(tuple._1, addRouteTableEntry(tuple._1, tuple._2)))
                .map(Tuple2::_1)
                .get();
    }

    private Option<Subnet> describeSubnet(Subnet subnet) {
        DescribeSubnetsRequest describeSubnetsRequest = DescribeSubnetsRequest.builder()
                .subnetIds(subnet.subnetId())
                .build();

        return Option.of(ec2Client().describeSubnets(describeSubnetsRequest))
                .map(DescribeSubnetsResponse::subnets)
                .map(List::ofAll)
                .flatMap(Traversable::headOption);
    }

    private ModifySubnetAttributeResponse mapPublicIpOnLaunch(Subnet subnet) {
        ModifySubnetAttributeRequest modifySubnetAttributeRequest = ModifySubnetAttributeRequest.builder()
                .mapPublicIpOnLaunch(TRUE_ATTRIBUTE)
                .subnetId(subnet.subnetId())
                .build();

        return ec2Client().modifySubnetAttribute(modifySubnetAttributeRequest);
    }

    private List<Subnet> getSubnetsInVpc(Vpc vpc) {
        Filter vpcIdFilter = Filter.builder().name(VPC_ID).values(vpc.vpcId()).build();

        DescribeSubnetsRequest describeSubnetsRequest = DescribeSubnetsRequest.builder()
                .filters(vpcIdFilter)
                .build();

        return Option.of(ec2Client().describeSubnets(describeSubnetsRequest))
                .map(DescribeSubnetsResponse::subnets)
                .map(List::ofAll)
                .get();
    }

    private Option<Vpc> getVpcByName(String nameTag) {
        return Failsafe.with(doesNotExistFailsafeOptionPolicy(Vpc.class, "VPC - " + nameTag, 3))
                .get(() -> getVpcsByName(nameTag).headOption());
    }

    private List<Vpc> getVpcsByName(String nameTag) {
        Filter filter = Filter.builder().name(String.join(":", "tag", VPC_NAME_TAG)).values(nameTag).build();

        DescribeVpcsRequest describeVpcsRequest = DescribeVpcsRequest.builder().filters(filter).build();

        return Option.of(ec2Client().describeVpcs(describeVpcsRequest))
                .map(DescribeVpcsResponse::vpcs)
                .map(List::ofAll)
                .get();
    }

    private boolean namedVpcExists(String nameTag) {
        Filter filter = Filter.builder().name(String.join(":", "tag", VPC_NAME_TAG)).values(nameTag).build();

        DescribeVpcsRequest describeVpcsRequest = DescribeVpcsRequest.builder().filters(filter).build();

        return Option.of(ec2Client().describeVpcs(describeVpcsRequest))
                .map(DescribeVpcsResponse::vpcs)
                .map(vpcs -> vpcs.size() != 0)
                .get();
    }

    private RunInstancesResponse runInstance(AMI ami, String instanceType, String keyName, SecurityGroup securityGroup, Subnet subnet, IamInstanceProfileSpecification iamInstanceProfileSpecification) {
        EbsBlockDevice ebsBlockDevice = EbsBlockDevice.builder()
                .deleteOnTermination(true)
                .volumeType(VolumeType.GP3)
                .volumeSize(100)
                .build();

        BlockDeviceMapping blockDeviceMapping = BlockDeviceMapping.builder()
                .ebs(ebsBlockDevice)
                .deviceName("/dev/sda1")
                .build();

        Tag toolNameTag = Tag.builder()
                .key(App.TOOL_NAME)
                .value(App.TOOL_NAME)
                .build();

        TagSpecification tagSpecification = TagSpecification.builder()
                .tags(toolNameTag)
                .resourceType(ResourceType.INSTANCE)
                .build();

        RunInstancesRequest runInstancesRequest = RunInstancesRequest.builder()
                .imageId(ami.id())
                .instanceType(InstanceType.fromValue(instanceType))
                .minCount(1)
                .maxCount(1)
                .keyName(keyName)
                .subnetId(subnet.subnetId())
                .securityGroupIds(securityGroup.groupId())
                .blockDeviceMappings(blockDeviceMapping)
                .tagSpecifications(tagSpecification)
                .iamInstanceProfile(iamInstanceProfileSpecification)
                .build();

        return Failsafe.with(doesNotExistFailsafePolicy(RunInstancesResponse.class, "EC2 instance", 3))
                .onFailure(event -> log().error("Failed to run instance: {}", event.getFailure().getMessage()))
                .get(() -> ec2Client().runInstances(runInstancesRequest));
    }

    private Vpc createVpc() {
        CreateVpcRequest createVpcRequest = CreateVpcRequest.builder()
                .cidrBlock(VPC_CIDR_BLOCK)
                .build();

        return Option.of(ec2Client().createVpc(createVpcRequest))
                .map(CreateVpcResponse::vpc)
                .get();
    }

    private CreateRouteResponse addRouteTableEntry(Subnet subnet, String internetGatewayId) {
        Filter vpcIdFilter = Filter.builder().name(VPC_ID).values(subnet.vpcId()).build();

        DescribeRouteTablesRequest describeRouteTablesRequest = DescribeRouteTablesRequest.builder()
                .filters(vpcIdFilter)
                .build();

        String routeTableId = Option.of(ec2Client().describeRouteTables(describeRouteTablesRequest))
                .map(DescribeRouteTablesResponse::routeTables)
                .map(List::ofAll)
                .map(List::head)
                .map(RouteTable::routeTableId)
                .get();

        CreateRouteRequest createRouteRequest = CreateRouteRequest.builder()
                .destinationCidrBlock(ANY_IP_CIDR)
                .routeTableId(routeTableId)
                .gatewayId(internetGatewayId)
                .build();

        return Failsafe.with(doesNotExistFailsafePolicy(CreateRouteResponse.class, "Route", 3))
                .get(() -> ec2Client().createRoute(createRouteRequest));
    }

    private String attachInternetGatewayToVpc(Vpc vpc) {
        return Option.of(CreateInternetGatewayRequest.builder().build())
                .map(createInternetGatewayRequest -> ec2Client().createInternetGateway(createInternetGatewayRequest))
                .map(CreateInternetGatewayResponse::internetGateway)
                .map(InternetGateway::internetGatewayId)
                .map(internetGatewayId -> Tuple.of(internetGatewayId, AttachInternetGatewayRequest.builder().internetGatewayId(internetGatewayId).vpcId(vpc.vpcId()).build()))
                .map(tuple -> tuple.map2(ec2Client()::attachInternetGateway))
                .map(Tuple2::_1)
                .get();
    }

    private Vpc createAndTagVpcWithName(String nameTag) {
        return Option.of(createVpc())
                // Tag the VPC with the name
                .map(vpc -> Tuple.of(vpc, tagVpcWithName(vpc.vpcId(), nameTag))).map(Tuple2::_1).get();
    }

    private <T> RetryPolicy<T> doesNotExistFailsafePolicy(Class<T> clazz, String resourceName, int retries) {
        return configureDoesNotExistRetryPolicy(new RetryPolicy<>(), resourceName, retries);
    }

    private <T> RetryPolicy<Option<T>> doesNotExistFailsafeOptionPolicy(Class<T> clazz, String resourceName, int retries) {
        return configureDoesNotExistRetryPolicy(new RetryPolicy<>(), resourceName, retries);
    }

    private <T> RetryPolicy<T> configureDoesNotExistRetryPolicy(RetryPolicy<T> retryPolicy, String resourceName, int retries) {
        return retryPolicy
                .withMaxRetries(retries)
                .onFailedAttempt(response -> println("Resource does not exist: {}", resourceName))
                .withDelay(Duration.ofSeconds(1))
                .handleIf(e -> e.getMessage().contains("does not exist"));
    }

    private <T> RetryPolicy<T> nullFailsafePolicyWithStatus(Class<T> clazz, String resourceName, int retries) {
        return nullFailsafePolicyWithoutStatus(clazz, retries)
                .onFailedAttempt(response -> println("Resource is null: {}", resourceName));
    }

    private <T> RetryPolicy<T> nullFailsafePolicyWithoutStatus(Class<T> clazz, int retries) {
        return new RetryPolicy<T>().withMaxRetries(retries).withDelay(Duration.ofSeconds(1)).handleResultIf(Objects::isNull);
    }

    private CreateTagsResponse tagVpcWithName(String vpcId, String nameTag) {
        Tag tag = Tag.builder().key(VPC_NAME_TAG).value(nameTag).build();

        CreateTagsRequest createTagsRequest = CreateTagsRequest.builder()
                .resources(vpcId)
                .tags(tag)
                .build();

        return Failsafe.with(doesNotExistFailsafePolicy(CreateTagsResponse.class, "VPC - " + vpcId + " - " + nameTag, 3)).get(() -> ec2Client().createTags(createTagsRequest));
    }

    private SecurityGroup getSecurityGroupByName(String securityGroupName) {
        Filter groupNameFilter = getSecurityGroupNameFilter(securityGroupName);

        DescribeSecurityGroupsRequest describeRequest = DescribeSecurityGroupsRequest.builder()
                .filters(groupNameFilter)
                .build();

        return Option.of(ec2Client().describeSecurityGroups(describeRequest))
                .map(DescribeSecurityGroupsResponse::securityGroups)
                .filter(securityGroups -> securityGroups.size() != 0)
                .map(securityGroups -> securityGroups.get(0))
                .get();
    }

    private List<SecurityGroup> getSecurityGroupsByName(String securityGroupName) {
        Filter groupNameFilter = getSecurityGroupNameFilter(securityGroupName);

        DescribeSecurityGroupsRequest describeRequest = DescribeSecurityGroupsRequest.builder()
                .filters(groupNameFilter)
                .build();

        return Option.of(ec2Client().describeSecurityGroups(describeRequest))
                .map(DescribeSecurityGroupsResponse::securityGroups)
                .map(List::ofAll)
                .get();
    }

    private Filter getSecurityGroupNameFilter(String securityGroupName) {
        return Filter.builder().name(GROUP_NAME).values(securityGroupName).build();
    }

    private boolean securityGroupExists(String securityGroupName) {
        return Option.of(getSecurityGroupsByName(securityGroupName))
                .map(securityGroups -> securityGroups.size() != 0)
                .get();
    }

    private String createSecurityGroup(String vpcId, String securityGroupName) {
        CreateSecurityGroupRequest createRequest = CreateSecurityGroupRequest.builder()
                .groupName(securityGroupName)
                .description("Security group for " + App.TOOL_NAME + " tool")
                .vpcId(vpcId)
                .build();

        return Option.of(ec2Client().createSecurityGroup(createRequest))
                .map(CreateSecurityGroupResponse::groupId)
                .get();
    }

    private AuthorizeSecurityGroupIngressResponse authorizeSecurityGroupIngress(String securityGroupId) {
        IpRange anyIp = IpRange.builder().cidrIp(ANY_IP_CIDR).build();

        IpPermission sshIpPermission = IpPermission.builder()
                .ipProtocol(TCP)
                .toPort(SSH_TCP_PORT)
                .fromPort(SSH_TCP_PORT)
                .ipRanges(anyIp)
                .build();

        IpPermission moshIpPermission = IpPermission.builder()
                .ipProtocol(UDP)
                .fromPort(MOSH_UDP_START_PORT)
                .toPort(MOSH_UDP_END_PORT)
                .ipRanges(anyIp)
                .build();

        AuthorizeSecurityGroupIngressRequest authorizeSecurityGroupIngressRequest = AuthorizeSecurityGroupIngressRequest.builder()
                .groupId(securityGroupId)
                .ipPermissions(sshIpPermission, moshIpPermission)
                .build();

        return ec2Client().authorizeSecurityGroupIngress(authorizeSecurityGroupIngressRequest);
    }
}
