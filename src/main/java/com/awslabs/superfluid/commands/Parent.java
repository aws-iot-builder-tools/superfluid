package com.awslabs.superfluid.commands;

import com.awslabs.superfluid.commands.greeneyes.GreenEyes;
import picocli.CommandLine;

@CommandLine.Command(name = "", subcommands = {
        GreenEyes.class
})

public class Parent {
}
