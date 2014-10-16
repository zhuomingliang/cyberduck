package ch.cyberduck.ui.browser;

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

import ch.cyberduck.core.BookmarkCollection;
import ch.cyberduck.core.Host;
import ch.cyberduck.core.Local;
import ch.cyberduck.core.LocalFactory;
import ch.cyberduck.core.Preferences;

import org.apache.log4j.Logger;

/**
 * @version $Id$
 */
public class UploadDirectoryFinder implements DirectoryFinder {
    private static final Logger log = Logger.getLogger(UploadDirectoryFinder.class);

    private Preferences preferences
            = Preferences.instance();

    @Override
    public Local find(final Host bookmark) {
        if(null != bookmark.getUploadFolder()) {
            if(bookmark.getUploadFolder().exists()) {
                return bookmark.getUploadFolder();
            }
        }
        final Local directory = LocalFactory.createLocal(preferences.getProperty("local.user.home"));
        if(log.isInfoEnabled()) {
            log.info(String.format("Suggest default upload folder %s for bookmark %s", directory, bookmark));
        }
        return directory;

    }

    @Override
    public void save(final Host bookmark, final Local directory) {
        if(!directory.exists()) {
            return;
        }
        if(log.isInfoEnabled()) {
            log.info(String.format("Save default upload folder %s for bookmark %s", directory, bookmark));
        }
        bookmark.setUploadFolder(directory);
        if(BookmarkCollection.defaultCollection().contains(bookmark)) {
            BookmarkCollection.defaultCollection().collectionItemChanged(bookmark);
        }
    }
}