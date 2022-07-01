package com.awslabs.superfluid.commands.greeneyes;

import com.awslabs.superfluid.helpers.Shared;
import picocli.CommandLine;

@CommandLine.Command(name = "greeneyes", mixinStandardHelpOptions = true,
        subcommands = {GreenEyesCleanup.class})
public class GreenEyes {
    // Shared with sub-commands via CommandLine.ScopeType.INHERIT
    @CommandLine.Option(names = "-v", scope = CommandLine.ScopeType.INHERIT)
    public void setVerbose(boolean[] verbose) {
        Shared.setVerbose(verbose);
    }
}
