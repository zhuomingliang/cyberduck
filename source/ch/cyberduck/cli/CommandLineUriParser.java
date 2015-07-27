package ch.cyberduck.cli;

/*
 * Copyright (c) 2002-2014 David Kocher. All rights reserved.
 * http://cyberduck.io/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Bug fixes, suggestions and comments should be sent to:
 * feedback@cyberduck.io
 */

import ch.cyberduck.core.Host;
import ch.cyberduck.core.HostParser;
import ch.cyberduck.core.Path;

import org.apache.commons.cli.CommandLine;

/**
 * @version $Id$
 */
public class CommandLineUriParser {

    private final CommandLine input;

    public CommandLineUriParser(final CommandLine input) {
        this.input = input;
    }

    public Host parse(final String uri) {
        final Host host = HostParser.parse(uri);
        switch(host.getProtocol().getType()) {
            case s3:
            case googlestorage:
            case swift:
                if(input.hasOption(TerminalOptionsBuilder.Params.region.name())) {
                    host.setRegion(input.getOptionValue(TerminalOptionsBuilder.Params.region.name()));
                }
        }
        final Path directory = new CommandLinePathParser(input).parse(uri);
        if(directory.isDirectory()) {
            host.setDefaultPath(directory.getAbsolute());
        }
        else {
            host.setDefaultPath(directory.getParent().getAbsolute());
        }
        if(input.hasOption(TerminalOptionsBuilder.Params.udt.name())) {
            host.setTransfer(Host.TransferType.udt);
        }
        return host;
    }
}