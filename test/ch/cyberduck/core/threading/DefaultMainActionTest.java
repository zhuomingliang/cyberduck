package ch.cyberduck.core.threading;

/*
 * Copyright (c) 2012 David Kocher. All rights reserved.
 * http://cyberduck.ch/
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
 * dkocher@cyberduck.ch
 */

import org.junit.Test;

import static org.junit.Assert.assertNotSame;

/**
 * @version $Id:$
 */
public class DefaultMainActionTest {

    @Test
    public void testLock() throws Exception {
        assertNotSame(new DefaultMainAction() {
            @Override
            public void run() {

            }
        }.lock(), new DefaultMainAction() {
            @Override
            public void run() {

            }
        }.lock());
    }
}
