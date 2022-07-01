## Superfluid

Superfluid is a cross-platform, command-line utility that aims to reduce as much friction for you when running common cloud tasks on AWS.

Currently the binary only supports cleaning up Greengrass resources associated with an upcoming blog post series but will be expanded to include several other features soon.

## Obtaining a pre-built binary

To obtain a pre-built binary head over to the [Superfluid releases](https://github.com/awslabs/superfluid/releases) page. Each release has 8 files associated with it:

- The source code compressed as a .tar.gz file
- The source code compressed as a .zip file
- A MacOS JAR file (contains MacOS specific dylibs)
- A Windows JAR file (contains Windows specific DLLs)
- A Linux JAR file (contains Linux specific shared objects)
- A standalone MacOS executable
- A standalone Windows executable (ending in .exe)
- A standaline Linux executable

Most users should obtain one of the standalone executables, rename it to `superfluid`, `sf`, `superfluid.exe`, `sf.exe`, or another short and convenient name, and add it to their path.

## Running the application

Once the application is in your path simply run it on the command-line with no options and you will see what operations it supports. It will look something like this:

```
Missing required subcommand
Usage:  [COMMAND]
Commands:
  greeneyes
```

## Windows notes

The Windows executable is significantly larger than the MacOS and Linux binaries. This is because the application that packs the executables down to a more manageable size has some compatibility issues with Windows 10. Packed executables for Windows will be distributed in future versions once they are stable.

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This project is licensed under the Apache-2.0 License.

