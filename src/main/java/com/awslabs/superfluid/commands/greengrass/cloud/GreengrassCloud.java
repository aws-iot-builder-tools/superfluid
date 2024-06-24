package com.awslabs.superfluid.commands.greengrass.cloud;

import com.awslabs.superfluid.helpers.Shared;
import com.awslabs.superfluid.commands.greeneyes.GreenEyesCleanup;
import picocli.CommandLine;

@CommandLine.Command(name = "ggc", mixinStandardHelpOptions = true,
        subcommands = {GreengrassGetRecipe.class, GreengrassShowDeployment.class})
public class GreengrassCloud {
    // Shared with sub-commands via CommandLine.ScopeType.INHERIT
    @CommandLine.Option(names = "-v", scope = CommandLine.ScopeType.INHERIT)
    public void setVerbose(boolean[] verbose) {
        Shared.setVerbose(verbose);
    }
}
