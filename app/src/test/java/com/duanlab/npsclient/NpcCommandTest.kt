package com.duanlab.npsclient

import org.junit.Assert.*
import org.junit.Test

class NpcCommandTest {
    @Test fun preservesOriginalEqualsSyntaxAndArgumentOrder() {
        val command = "-server=x.x.x.x:8024 -vkey=xxxxx -type=tcp " +
            "-debug=false -log=stdout -log_color=false"
        assertEquals(command.split(" "), NpcCommand.parse(command))
    }

    @Test fun preservesCommaListsEqualsInValuesAndQuotedEqualsValues() {
        assertEquals(listOf("-server=xxx:123,yyy:456", "-vkey=a=b==,key2", "-type=tls,tcp",
            "-log_level=6", "-debug=false", "-log=stdout", "-log_color=false"),
            NpcCommand.parse("-server=\"xxx:123,yyy:456\" -vkey='a=b==,key2' " +
                "-type=tls,tcp -log_level=6"))
    }

    @Test fun keepsSubcommandAndAddsOnlyMissingDefaults() {
        assertEquals(listOf("nat", "-server=host:8024", "-debug=true", "-log=stderr", "-log_color=false"),
            NpcCommand.parse("npc nat -server=host:8024 -debug=true -log=stderr"))
    }

    @Test fun preservesSeparateValuesAndQuotedWhitespace() {
        val args = NpcCommand.parse("-server host:8024\t-vkey 'a b' -log=\"some file\"")
        assertEquals(listOf("-server", "host:8024", "-vkey", "a b", "-log=some file",
            "-debug=false", "-log_color=false"), args)
    }

    @Test fun preservesExplicitDefaultsAndStandaloneSubcommands() {
        assertEquals(listOf("status", "--debug", "true", "--log=stderr", "-log_color=true"),
            NpcCommand.parse("./npc status --debug true --log=stderr -log_color=true"))
        assertEquals("register", NpcCommand.parse("register").first())
    }

    @Test fun handlesEscapesEmptyValuesAndLiteralShellCharacters() {
        val args = NpcCommand.parse("-vkey='' -name=a\\ b '-literal=\$HOME; echo x' -path='a\\b'")
        assertEquals(listOf("-vkey=", "-name=a b", "-literal=\$HOME; echo x", "-path=a\\b"), args.take(4))
    }

    @Test fun addsDefaultsBeforeEndOfOptions() {
        assertEquals(listOf("-debug=false", "-log=stdout", "-log_color=false", "--", "-log=foo"),
            NpcCommand.parse("-- -log=foo"))
    }

    @Test fun rejectsIncompleteCommands() {
        for (command in listOf("", "  ", "npc", "-vkey='abc", "-vkey=abc\\")) {
            assertThrows(IllegalArgumentException::class.java) { NpcCommand.parse(command) }
        }
    }
}
