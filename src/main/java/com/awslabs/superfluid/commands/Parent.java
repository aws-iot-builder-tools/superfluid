package com.awslabs.superfluid.commands;

import com.awslabs.superfluid.commands.ec2.Ec2;
import com.awslabs.superfluid.commands.greeneyes.GreenEyes;
import picocli.CommandLine;

@CommandLine.Command(name = "", subcommands = {
        Ec2.class,
        GreenEyes.class
})

public class Parent {
}
