package com.jdsp.cli;

/** Command-line parsing: commands, flags, option placement and usage errors. */
public final class ParserTest {
    private ParserTest() {}

    public static void run(Suite suite) {
        suite.test("parser: no arguments is a usage error", () ->
                Assert.cliCode(Exit.USAGE, () -> CommandParser.parse(new String[0])));

        suite.test("parser: null argv is a usage error", () ->
                Assert.cliCode(Exit.USAGE, () -> CommandParser.parse(null)));

        suite.test("parser: unknown command", () ->
                Assert.cliCode(Exit.USAGE, () -> CommandParser.parse(new String[]{"nope"})));

        suite.test("parser: no command after options", () ->
                Assert.cliCode(Exit.USAGE, () -> CommandParser.parse(new String[]{"--json"})));

        suite.test("parser: sessions --json", () -> {
            ParsedCommand cmd = CommandParser.parse(new String[]{"sessions", "--json"});
            Assert.eq("sessions", cmd.command());
            Assert.eq(Boolean.TRUE, cmd.json());
            Assert.eq(0, cmd.argCount());
        });

        suite.test("parser: --json before the command", () -> {
            ParsedCommand cmd = CommandParser.parse(new String[]{"--json", "sessions"});
            Assert.eq("sessions", cmd.command());
            Assert.eq(Boolean.TRUE, cmd.json());
        });

        suite.test("parser: options after positional values", () -> {
            ParsedCommand cmd = CommandParser.parse(
                    new String[]{"set", "12", "1500", "0.5", "--json"});
            Assert.eq("set", cmd.command());
            Assert.eq(3, cmd.argCount());
            Assert.eq("0.5", cmd.arg(2));
            Assert.eq(Boolean.TRUE, cmd.json());
        });

        suite.test("parser: negative floats stay positional", () -> {
            ParsedCommand cmd = CommandParser.parse(
                    new String[]{"set", "12", "1500", "-0.1", "60", "3.0"});
            Assert.eq(5, cmd.argCount());
            Assert.eq("-0.1", cmd.arg(2));
            Assert.isTrue(!cmd.hasValueOptions(), "unexpected value options");
        });

        suite.test("parser: --hex value", () -> {
            ParsedCommand cmd = CommandParser.parse(
                    new String[]{"set", "1", "2", "--hex", "0000803f"});
            Assert.eq("0000803f", cmd.hex());
            Assert.eq(2, cmd.argCount());
        });

        suite.test("parser: --hex=inline value", () -> {
            ParsedCommand cmd = CommandParser.parse(
                    new String[]{"set", "1", "2", "--hex=0000803f"});
            Assert.eq("0000803f", cmd.hex());
        });

        suite.test("parser: --int and --short values", () -> {
            ParsedCommand one = CommandParser.parse(new String[]{"set", "1", "2", "--int", "5"});
            Assert.eq("5", one.intValue());
            ParsedCommand two = CommandParser.parse(new String[]{"set", "1", "2", "--short=-2"});
            Assert.eq("-2", two.shortValue());
        });

        suite.test("parser: --raw flag and --iters value", () -> {
            ParsedCommand one = CommandParser.parse(new String[]{"set", "1", "2", "--raw", "dead"});
            Assert.eq(Boolean.TRUE, one.raw());
            Assert.eq(3, one.argCount());
            ParsedCommand two = CommandParser.parse(
                    new String[]{"bench", "1", "2", "0.5", "--iters", "10"});
            Assert.eq("10", two.iters());
            Assert.eq(3, two.argCount());
        });

        suite.test("parser: unknown option", () ->
                Assert.cliCode(Exit.USAGE, () ->
                        CommandParser.parse(new String[]{"sessions", "--bogus"})));

        suite.test("parser: option missing its value", () ->
                Assert.cliCode(Exit.USAGE, () ->
                        CommandParser.parse(new String[]{"set", "1", "2", "--hex"})));

        suite.test("parser: duplicate option", () ->
                Assert.cliCode(Exit.USAGE, () -> CommandParser.parse(
                        new String[]{"set", "1", "2", "--hex", "00", "--hex", "11"})));

        suite.test("parser: flag with inline value", () ->
                Assert.cliCode(Exit.USAGE, () ->
                        CommandParser.parse(new String[]{"--json=1", "sessions"})));

        suite.test("parser: -- ends option parsing", () -> {
            ParsedCommand cmd = CommandParser.parse(
                    new String[]{"set", "1", "2", "--", "--hex"});
            Assert.eq(3, cmd.argCount());
            Assert.eq("--hex", cmd.arg(2));
            Assert.isTrue(cmd.hex() == null, "--hex must stay positional after --");
        });

        suite.test("parser: help command", () -> {
            ParsedCommand cmd = CommandParser.parse(new String[]{"help"});
            Assert.eq("help", cmd.command());
            Assert.eq(0, cmd.argCount());
        });

        suite.test("parser: --help flag anywhere", () -> {
            ParsedCommand cmd = CommandParser.parse(new String[]{"ping", "--help"});
            Assert.eq("ping", cmd.command());
            Assert.eq(Boolean.TRUE, cmd.help());
        });

        suite.test("parser: -h alias", () -> {
            ParsedCommand cmd = CommandParser.parse(new String[]{"-h"});
            Assert.eq(Boolean.TRUE, cmd.help());
            Assert.eq("help", cmd.command());
        });

        suite.test("parser: policy subcommand args", () -> {
            ParsedCommand cmd = CommandParser.parse(
                    new String[]{"policy", "set", "com.example.music", "exclude"});
            Assert.eq("policy", cmd.command());
            Assert.eq(3, cmd.argCount());
        });

        suite.test("parser: intArg rejects non-integers", () -> {
            ParsedCommand cmd = CommandParser.parse(new String[]{"get", "abc", "7"});
            Assert.cliCode(Exit.USAGE, () -> cmd.intArg(0, "session"));
        });

        suite.test("parser: --tcp flag before the command", () -> {
            ParsedCommand cmd = CommandParser.parse(new String[]{"--tcp", "sessions"});
            Assert.eq(Boolean.TRUE, cmd.tcp());
            Assert.eq(null, cmd.tcpEndpoint());
            Assert.eq("sessions", cmd.command());
        });

        suite.test("parser: --tcp after the command", () -> {
            ParsedCommand cmd = CommandParser.parse(new String[]{"sessions", "--tcp"});
            Assert.eq(Boolean.TRUE, cmd.tcp());
            Assert.eq("sessions", cmd.command());
        });

        suite.test("parser: --tcp=host:port", () -> {
            ParsedCommand cmd = CommandParser.parse(
                    new String[]{"--tcp=127.0.0.1:4321", "ping"});
            Assert.eq("127.0.0.1:4321", cmd.tcpEndpoint());
            Assert.eq("ping", cmd.command());
        });

        suite.test("parser: --tcp host:port and --tcp port", () -> {
            ParsedCommand cmd = CommandParser.parse(
                    new String[]{"--tcp", "127.0.0.1:4321", "get", "1", "2"});
            Assert.eq("127.0.0.1:4321", cmd.tcpEndpoint());
            Assert.eq("get", cmd.command());
            Assert.eq(2, cmd.argCount());
            ParsedCommand portOnly = CommandParser.parse(new String[]{"--tcp", "4321", "ping"});
            Assert.eq("4321", portOnly.tcpEndpoint());
            Assert.eq("ping", portOnly.command());
        });

        suite.test("parser: --tcp followed by the command keeps the default endpoint", () -> {
            ParsedCommand cmd = CommandParser.parse(new String[]{"--tcp", "policy", "list"});
            Assert.eq(Boolean.TRUE, cmd.tcp());
            Assert.eq(null, cmd.tcpEndpoint());
            Assert.eq("policy", cmd.command());
            Assert.eq(1, cmd.argCount());
        });

        suite.test("parser: duplicate --tcp rejected", () ->
                Assert.cliCode(Exit.USAGE, () ->
                        CommandParser.parse(new String[]{"--tcp", "--tcp", "ping"})));

        suite.test("parser: --tcp= with an empty endpoint rejected", () ->
                Assert.cliCode(Exit.USAGE, () ->
                        CommandParser.parse(new String[]{"--tcp=", "ping"})));
    }
}
