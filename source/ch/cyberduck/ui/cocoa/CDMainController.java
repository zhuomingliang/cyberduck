package ch.cyberduck.ui.cocoa;

/*
 *  Copyright (c) 2005 David Kocher. All rights reserved.
 *  http://cyberduck.ch/
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Bug fixes, suggestions and comments should be sent to:
 *  dkocher@cyberduck.ch
 */

import ch.cyberduck.core.Host;
import ch.cyberduck.core.Local;
import ch.cyberduck.core.Message;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.PathFactory;
import ch.cyberduck.core.Preferences;
import ch.cyberduck.core.Queue;
import ch.cyberduck.core.Rendezvous;
import ch.cyberduck.core.Session;
import ch.cyberduck.core.UploadQueue;
import ch.cyberduck.core.Version;
import ch.cyberduck.ui.cocoa.growl.Growl;

import com.apple.cocoa.application.NSAlertPanel;
import com.apple.cocoa.application.NSApplication;
import com.apple.cocoa.application.NSButton;
import com.apple.cocoa.application.NSCell;
import com.apple.cocoa.application.NSImage;
import com.apple.cocoa.application.NSMenu;
import com.apple.cocoa.application.NSMenuItem;
import com.apple.cocoa.application.NSTextField;
import com.apple.cocoa.application.NSTextView;
import com.apple.cocoa.application.NSWindow;
import com.apple.cocoa.application.NSWorkspace;
import com.apple.cocoa.foundation.NSArray;
import com.apple.cocoa.foundation.NSAutoreleasePool;
import com.apple.cocoa.foundation.NSBundle;
import com.apple.cocoa.foundation.NSData;
import com.apple.cocoa.foundation.NSDictionary;
import com.apple.cocoa.foundation.NSKeyValue;
import com.apple.cocoa.foundation.NSMutableArray;
import com.apple.cocoa.foundation.NSMutableData;
import com.apple.cocoa.foundation.NSMutableDictionary;
import com.apple.cocoa.foundation.NSNotification;
import com.apple.cocoa.foundation.NSNotificationCenter;
import com.apple.cocoa.foundation.NSObject;
import com.apple.cocoa.foundation.NSPathUtilities;
import com.apple.cocoa.foundation.NSPropertyListSerialization;
import com.apple.cocoa.foundation.NSRange;
import com.apple.cocoa.foundation.NSSelector;
import com.apple.cocoa.foundation.NSSize;

import org.apache.log4j.BasicConfigurator;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.List;
import java.util.ArrayList;

/**
 * @version $Id$
 */
public class CDMainController extends CDController {
    private static org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(CDMainController.class);

    private static final File VERSION_FILE = new File(NSPathUtilities.stringByExpandingTildeInPath("~/Library/Application Support/Cyberduck/Version.plist"));

    static {
        BasicConfigurator.configure();
        org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.toLevel(Preferences.instance().getProperty("logging")));
    }

    public void awakeFromNib() {
        super.awakeFromNib();
        NSNotificationCenter.defaultCenter().addObserver(this,
                new NSSelector("applicationShouldSleep", new Class[]{Object.class}),
                NSWorkspace.WorkspaceWillSleepNotification,
                null);

        NSNotificationCenter.defaultCenter().addObserver(this,
                new NSSelector("applicationShouldWake", new Class[]{Object.class}),
                NSWorkspace.WorkspaceDidWakeNotification,
                null);
    }

    // ----------------------------------------------------------
    // Outlets
    // ----------------------------------------------------------

    private NSWindow donationSheet; // IBOutlet

    public void setDonationSheet(NSWindow donationSheet) {
        this.donationSheet = donationSheet;
    }

    private NSButton neverShowDonationCheckbox;

    public void setNeverShowDonationCheckbox(NSButton neverShowDonationCheckbox) {
        this.neverShowDonationCheckbox = neverShowDonationCheckbox;
        this.neverShowDonationCheckbox.setTarget(this);
        this.neverShowDonationCheckbox.setState(Preferences.instance().getProperty("donate").equals("false") ? NSCell.OnState : NSCell.OffState);
    }

    private NSButton autoUpdateCheckbox;

    public void setAutoUpdateCheckbox(NSButton autoUpdateCheckbox) {
        this.autoUpdateCheckbox = autoUpdateCheckbox;
        this.autoUpdateCheckbox.setTarget(this);
        this.autoUpdateCheckbox.setAction(new NSSelector("autoUpdateCheckboxClicked", new Class[]{NSButton.class}));
        this.autoUpdateCheckbox.setState(Preferences.instance().getBoolean("update.check") ? NSCell.OnState : NSCell.OffState);
    }

    public void autoUpdateCheckboxClicked(NSButton sender) {
        Preferences.instance().setProperty("update.check", sender.state() == NSCell.OnState);
    }

    public NSWindow updateSheet; // IBOutlet

    public void setUpdateSheet(NSWindow updateSheet) {
        this.updateSheet = updateSheet;
    }

    private NSTextField updateLabel; // IBOutlet

    public void setUpdateLabel(NSTextField updateLabel) {
        this.updateLabel = updateLabel;
    }

    private NSTextView updateText; // IBOutlet

    public void setUpdateText(NSTextView updateText) {
        this.updateText = updateText;
    }

    private NSMenu encodingMenu;

    public void setEncodingMenu(NSMenu encodingMenu) {
        this.encodingMenu = encodingMenu;
        java.util.SortedMap charsets = java.nio.charset.Charset.availableCharsets();
        java.util.Iterator iterator = charsets.values().iterator();
        while (iterator.hasNext()) {
            this.encodingMenu.addItem(new NSMenuItem(((java.nio.charset.Charset) iterator.next()).name(),
                    new NSSelector("encodingButtonClicked", new Class[]{Object.class}),
                    ""));
        }
    }

    private NSMenu bookmarkMenu;
    private NSObject bookmarkMenuDelegate;
    private NSMenu rendezvousMenu;
    private NSObject rendezvousMenuDelegate;
    private NSMenu historyMenu;
    private NSObject historyMenuDelegate;
    private Rendezvous rendezvous;

    public void setBookmarkMenu(NSMenu bookmarkMenu) {
        this.bookmarkMenu = bookmarkMenu;
        this.rendezvousMenu = new NSMenu();
        this.rendezvousMenu.setAutoenablesItems(false);
        this.historyMenu = new NSMenu();
        this.historyMenu.setAutoenablesItems(false);
        this.bookmarkMenu.setDelegate(this.bookmarkMenuDelegate = new BookmarkMenuDelegate());
        this.historyMenu.setDelegate(this.historyMenuDelegate = new HistoryMenuDelegate());
        this.rendezvousMenu.setDelegate(this.rendezvousMenuDelegate = new RendezvousMenuDelegate(
                this.rendezvous = Rendezvous.instance()));
        this.bookmarkMenu.itemWithTitle(NSBundle.localizedString("History", "")).setAction(
                new NSSelector("historyMenuClicked", new Class[]{NSMenuItem.class})
        );
        this.bookmarkMenu.setSubmenuForItem(historyMenu, this.bookmarkMenu.itemWithTitle(
                NSBundle.localizedString("History", "")));
        this.bookmarkMenu.setSubmenuForItem(rendezvousMenu, this.bookmarkMenu.itemWithTitle("Bonjour"));
    }

    public void historyMenuClicked(NSMenuItem sender) {
        NSWorkspace.sharedWorkspace().selectFile(HISTORY_FOLDER.getAbsolutePath(), "");
    }

    private class BookmarkMenuDelegate extends NSObject {
        private Map items = new HashMap();

        public int numberOfItemsInMenu(NSMenu menu) {
            return CDBookmarkTableDataSource.instance().size() + 8;
            //index 0-2 are static menu items, 3 is sepeartor, 4 is iDisk with submenu, 5 is Rendezvous with submenu,
            // 6 is History with submenu, 7 is sepearator
        }

        /**
         * Called to let you update a menu item before it is displayed. If your
         * numberOfItemsInMenu delegate method returns a positive value,
         * then your menuUpdateItemAtIndex method is called for each item in the menu.
         * You can then update the menu title, image, and so forth for the menu item.
         * Return true to continue the process. If you return false, your menuUpdateItemAtIndex
         * is not called again. In that case, it is your responsibility to trim any extra items from the menu.
         */
        public boolean menuUpdateItemAtIndex(NSMenu menu, NSMenuItem item, int index, boolean shouldCancel) {
            if (index == 4) {
                item.setEnabled(true);
                NSImage icon = NSImage.imageNamed("idisk.tiff");
                icon.setScalesWhenResized(true);
                icon.setSize(new NSSize(16f, 16f));
                item.setImage(icon);
            }
            if (index == 5) {
                item.setEnabled(true);
                item.setImage(NSImage.imageNamed("history.tiff"));
            }
            if (index == 6) {
                item.setEnabled(true);
                item.setImage(NSImage.imageNamed("rendezvous16.tiff"));
            }
            if (index > 7) {
                Host h = (Host) CDBookmarkTableDataSource.instance().get(index - 8);
                item.setTitle(h.getNickname());
                item.setTarget(this);
                item.setImage(NSImage.imageNamed("bookmark16.tiff"));
                item.setAction(new NSSelector("bookmarkMenuItemClicked", new Class[]{Object.class}));
                items.put(item, h);
            }
            return true;
        }

        public void bookmarkMenuItemClicked(Object sender) {
            log.debug("bookmarkMenuItemClicked:" + sender);
            CDBrowserController controller = CDMainController.this.newDocument();
            controller.mount((Host) items.get(sender));
        }
    }

    private static final File HISTORY_FOLDER = new File(
            NSPathUtilities.stringByExpandingTildeInPath("~/Library/Application Support/Cyberduck/History"));

    private class HistoryMenuDelegate extends NSObject {

        private Host[] cache = null;

        private File[] listFiles() {
            File[] files = HISTORY_FOLDER.listFiles(new java.io.FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return name.endsWith(".duck");
                }
            });
            Arrays.sort(files, new Comparator() {
                public int compare(Object o1, Object o2) {
                    File f1 = (File) o1;
                    File f2 = (File) o2;
                    if (f1.lastModified() < f2.lastModified()) {
                        return 1;
                    }
                    if (f1.lastModified() > f2.lastModified()) {
                        return -1;
                    }
                    return 0;
                }
            });
            return files;
        }

        public int numberOfItemsInMenu(NSMenu menu) {
            File[] files = this.listFiles();
            if (null == cache || cache.length != files.length) {
                cache = new Host[files.length];
                for (int i = 0; i < files.length; i++) {
                    cache[i] = CDBookmarkTableDataSource.instance().importBookmark(files[i]);
                }
            }
            if (files.length > 0) {
                return files.length;
            }
            return 1;
        }

        public boolean menuUpdateItemAtIndex(NSMenu menu, NSMenuItem sender, int index, boolean shouldCancel) {
            if (cache.length == 0) {
                sender.setTitle(NSBundle.localizedString("No recently connected servers available", ""));
                sender.setImage(null);
                sender.setEnabled(false);
                return false;
            }
            Host h = cache[index];
            sender.setTitle(h.toString());
            sender.setTarget(this);
            sender.setEnabled(true);
            sender.setImage(NSImage.imageNamed("bookmark16.tiff"));
            sender.setAction(new NSSelector("historyMenuItemClicked", new Class[]{NSMenuItem.class}));
            return !shouldCancel;
        }

        public void historyMenuItemClicked(NSMenuItem sender) {
            File[] items = this.listFiles();
            for (int i = 0; i < items.length; i++) {
                Host h = CDBookmarkTableDataSource.instance().importBookmark(items[i]);
                if (h.equals(sender.title())) {
                    CDBrowserController controller = CDMainController.this.newDocument();
                    controller.mount(h);
                }
            }
        }
    }

    private class RendezvousMenuDelegate extends NSObject implements Observer {
        private Map hosts = new HashMap();

        public RendezvousMenuDelegate(Rendezvous rendezvous) {
            log.debug("RendezvousMenuDelegate");
            rendezvous.addObserver(this);
        }

        public void update(final Observable o, final Object arg) {
            log.debug("update:" + o + "," + arg);
            if (o instanceof Rendezvous) {
                if (arg instanceof Message) {
                    Message msg = (Message) arg;
                    String identifier = (String)msg.getContent();
                    if (msg.getTitle().equals(Message.RENDEZVOUS_ADD)) {
                        Growl.instance().notifyWithImage("Bonjour", identifier, "rendezvous.icns");
                        hosts.put(Rendezvous.instance().getService(identifier).getNickname(),
                                identifier);
                    }
                    if (msg.getTitle().equals(Message.RENDEZVOUS_REMOVE)) {
                        hosts.remove(identifier);
                    }
                }
            }
        }

        public int numberOfItemsInMenu(NSMenu menu) {
            if (hosts.size() > 0) {
                return hosts.size();
            }
            return 1;
        }

        /**
         * Called to let you update a menu item before it is displayed. If your
         * numberOfItemsInMenu delegate method returns a positive value,
         * then your menuUpdateItemAtIndex method is called for each item in the menu.
         * You can then update the menu title, image, and so forth for the menu item.
         * Return true to continue the process. If you return false, your menuUpdateItemAtIndex
         * is not called again. In that case, it is your responsibility to trim any extra items from the menu.
         */
        public boolean menuUpdateItemAtIndex(NSMenu menu, NSMenuItem sender, int index, boolean shouldCancel) {
            if (hosts.size() == 0) {
                sender.setTitle(NSBundle.localizedString("No Bonjour services available", ""));
                sender.setEnabled(false);
                return !shouldCancel;
            }
            else {
                String title = ((String)this.hosts.keySet().toArray(new String[]{})[index]);
                sender.setTitle(title);
                sender.setTarget(this);
                sender.setEnabled(true);
                sender.setAction(new NSSelector("rendezvousMenuClicked", new Class[]{NSMenuItem.class}));
                return !shouldCancel;
            }
        }

        public void rendezvousMenuClicked(NSMenuItem sender) {
            CDBrowserController controller = CDMainController.this.newDocument();
            String identifier = (String)hosts.get(sender.title());
            controller.mount(Rendezvous.instance().getService(identifier));
        }
    }

    public void bugreportMenuClicked(Object sender) {
        try {
            NSWorkspace.sharedWorkspace().openURL(
                    new java.net.URL(Preferences.instance().getProperty("website.bug")));
        }
        catch (java.net.MalformedURLException e) {
            log.error(e.getMessage());
        }
    }

    public void helpMenuClicked(Object sender) {
        try {
            String locale = "en";
            NSArray preferredLocalizations = NSBundle.preferredLocalizations(NSBundle.mainBundle().localizations());
            if (preferredLocalizations.count() > 0) {
                locale = (String) preferredLocalizations.objectAtIndex(0);
            }
            NSWorkspace.sharedWorkspace().openURL(
                    new java.net.URL(Preferences.instance().getProperty("website.help") + locale + "/"));
        }
        catch (java.net.MalformedURLException e) {
            log.error(e.getMessage());
        }
    }

    public void faqMenuClicked(Object sender) {
        NSWorkspace.sharedWorkspace().openFile(
                new File(NSBundle.mainBundle().pathForResource("Cyberduck FAQ", "rtfd")).toString());
    }

    public void licenseMenuClicked(Object sender) {
        NSWorkspace.sharedWorkspace().openFile(
                new File(NSBundle.mainBundle().pathForResource("License", "txt")).toString());
    }

    public void updateMenuClicked(Object sender) {
        this.checkForUpdate(true);
    }

    public void checkForUpdate(final boolean verbose) {
        new Thread("Update") {
            public void run() {
                try {
                    int pool = NSAutoreleasePool.push();
                    String currentVersionNumber = (String) NSBundle.mainBundle().objectForInfoDictionaryKey("CFBundleVersion");
                    log.info("Current version:" + currentVersionNumber);
                    NSData data = new NSData(new java.net.URL(Preferences.instance().getProperty("website.update.xml")));
                    if (null == data) {
                        if (verbose) {
                            NSAlertPanel.runCriticalAlert(NSBundle.localizedString("Error", "Alert sheet title"), //title
                                    NSBundle.localizedString("There was a problem checking for an update. Please try again later.", "Alert sheet text"),
                                    NSBundle.localizedString("OK", "Alert sheet default button"), // defaultbutton
                                    null, //alternative button
                                    null//other button
                            );
                        }
                        return;
                    }
                    String[] errorString = new String[]{null};
                    Object propertyListFromXMLData =
                            NSPropertyListSerialization.propertyListFromData(data,
                                    NSPropertyListSerialization.PropertyListImmutable,
                                    new int[]{NSPropertyListSerialization.PropertyListXMLFormat},
                                    errorString);
                    if (errorString[0] != null || null == propertyListFromXMLData) {
                        log.error("Version info could not be retrieved: " + errorString[0]);
                        if (verbose) {
                            NSAlertPanel.runCriticalAlert(NSBundle.localizedString("Error", "Alert sheet title"), //title
                                    NSBundle.localizedString("There was a problem checking for an update. Please try again later.", "Alert sheet text") + " (" + errorString[0] + ")",
                                    NSBundle.localizedString("OK", "Alert sheet default button"), // defaultbutton
                                    null, //alternative button
                                    null//other button
                            );
                        }
                    }
                    else {
                        if (log.isInfoEnabled())
                            log.info(propertyListFromXMLData.toString());
                        NSDictionary entries = (NSDictionary) propertyListFromXMLData;
                        String latestVersionNumber = (String) entries.objectForKey("version");
                        log.info("Latest version:" + latestVersionNumber);
                        String filename = (String) entries.objectForKey("file");
                        String comment = (String) entries.objectForKey("comment");

                        Version currentVersion = new Version(currentVersionNumber);
                        Version latestVersion = new Version(latestVersionNumber);
                        if (currentVersion.compareTo(latestVersion) == 0) {
                            if (verbose) {
                                NSAlertPanel.runInformationalAlert(NSBundle.localizedString("No update", "Alert sheet title"), //title
                                        NSBundle.localizedString("No newer version available.", "Alert sheet text") + " Cyberduck " + currentVersionNumber + " " + NSBundle.localizedString("is up to date.", "Alert sheet text"),
                                        "OK", // defaultbutton
                                        null, //alternative button
                                        null//other button
                                );
                            }
                        }
                        else {
                            if (currentVersion.compareTo(latestVersion) < 0) {
                                // Update available, show update dialog
                                if (!NSApplication.loadNibNamed("Update", CDMainController.this)) {
                                    log.fatal("Couldn't load Update.nib");
                                    return;
                                }
                                updateLabel.setStringValue("Cyberduck " + currentVersionNumber + " " + NSBundle.localizedString("is out of date. The current version is", "Alert sheet text") + " " + latestVersionNumber + ".");
                                updateText.replaceCharactersInRange(new NSRange(updateText.textStorage().length(), 0), comment);
                                updateSheet.setTitle(filename);
                                updateSheet.center();
                                updateSheet.makeKeyAndOrderFront(null);
                            }
                            else {
                                if (verbose) {
                                    NSAlertPanel.runInformationalAlert(NSBundle.localizedString("No update", "Alert sheet title"), //title
                                            NSBundle.localizedString("No newer version available.", "Alert sheet text") + " Cyberduck " + currentVersionNumber + " " + NSBundle.localizedString("is up to date.", "Alert sheet text"),
                                            "OK", // defaultbutton
                                            null, //alternative button
                                            null//other button
                                    );

                                }
                            }
                        }
                    }
                    NSAutoreleasePool.pop(pool);
                }
                catch (Exception e) {
                    log.error(e.getMessage());
                }
            }
        }.start();
    }

    public void websiteMenuClicked(Object sender) {
        try {
            NSWorkspace.sharedWorkspace().openURL(new java.net.URL(Preferences.instance().getProperty("website.home")));
        }
        catch (java.net.MalformedURLException e) {
            log.error(e.getMessage());
        }
    }

    public void forumMenuClicked(Object sender) {
        try {
            NSWorkspace.sharedWorkspace().openURL(new java.net.URL(Preferences.instance().getProperty("website.forum")));
        }
        catch (java.net.MalformedURLException e) {
            log.error(e.getMessage());
        }
    }

    public void donateMenuClicked(Object sender) {
        try {
            NSWorkspace.sharedWorkspace().openURL(new java.net.URL(Preferences.instance().getProperty("website.donate")));
        }
        catch (java.net.MalformedURLException e) {
            log.error(e.getMessage());
        }
    }

    public void feedbackMenuClicked(Object sender) {
        try {
            String versionString = (String) NSBundle.bundleForClass(this.getClass()).objectForInfoDictionaryKey("CFBundleVersion");
            NSWorkspace.sharedWorkspace().openURL(new java.net.URL(Preferences.instance().getProperty("mail") + "?subject=Cyberduck-" + versionString));
        }
        catch (java.net.MalformedURLException e) {
            log.error(e.getMessage());
        }
    }

    public void closeUpdateSheet(NSButton sender) {
        updateSheet.close();
        if (sender.tag() == NSAlertPanel.DefaultReturn) {
            try {
                NSWorkspace.sharedWorkspace().openURL(new java.net.URL(Preferences.instance().getProperty("website.update") + updateSheet.title()));
            }
            catch (java.net.MalformedURLException e) {
                log.error(e.getMessage());
            }
        }
    }

    public void closeDonationSheet(NSButton sender) {
        donationSheet.close();
        Preferences.instance().setProperty("donate", neverShowDonationCheckbox.state() == NSCell.OffState);
        if (sender.tag() == NSAlertPanel.DefaultReturn) {
            try {
                NSWorkspace.sharedWorkspace().openURL(new java.net.URL(Preferences.instance().getProperty("website.donate")));
            }
            catch (java.net.MalformedURLException e) {
                log.error(e.getMessage());
            }
        }
    }

    public void preferencesMenuClicked(Object sender) {
        CDPreferencesController controller = CDPreferencesController.instance();
        controller.window().makeKeyAndOrderFront(null);
    }

    public void newDownloadMenuClicked(Object sender) {
        CDDownloadController controller = new CDDownloadController();
        controller.window().makeKeyAndOrderFront(null);
    }

    public void newBrowserMenuClicked(Object sender) {
        this.newDocument(true);
    }

    public void showTransferQueueClicked(Object sender) {
        CDQueueController controller = CDQueueController.instance();
        controller.window().makeKeyAndOrderFront(null);
    }

    public void downloadBookmarksFromDotMacClicked(Object sender) {
        CDDotMacController controller = new CDDotMacController();
        controller.downloadBookmarks();
    }

    public void uploadBookmarksToDotMacClicked(Object sender) {
        CDDotMacController controller = new CDDotMacController();
        controller.uploadBookmarks();
    }

    // ----------------------------------------------------------
    // Application delegate methods
    // ----------------------------------------------------------

    public boolean applicationOpenFile(NSApplication app, String filename) {
        log.debug("applicationOpenFile:" + filename);
        File f = new File(filename);
        if (f.exists()) {
            if (f.getAbsolutePath().endsWith(".duck")) {
                Host host = CDBookmarkTableDataSource.instance().importBookmark(f);
                if (host != null) {
                    this.newDocument().mount(host);
                    return true;
                }
            }
            else {
                NSArray windows = NSApplication.sharedApplication().windows();
                int count = windows.count();
                while (0 != count--) {
                    NSWindow window = (NSWindow) windows.objectAtIndex(count);
                    final CDBrowserController controller = CDBrowserController.controllerForWindow(window);
                    if (null != controller) {
                        if (controller.isMounted()) {
                            Path workdir = controller.workdir();
                            final Queue q = new UploadQueue();
                            q.addObserver(new Observer() {
                                public void update(Observable observable, Object arg) {
                                    Message msg = (Message) arg;
                                    if (msg.getTitle().equals(Message.QUEUE_STOP)) {
                                        if(controller.isMounted()) {
                                            controller.workdir().getSession().cache().invalidate(q.getRoot().getParent());
                                            controller.reloadData(true);
                                        }
                                    }
                                }
                            });
                            Session session = workdir.getSession().copy();
                            q.addRoot(PathFactory.createPath(session, workdir.getAbsolute(), new Local(f.getAbsolutePath())));
                            CDQueueController.instance().startItem(q);
                            break;
                        }
                    }
                }
            }
        }
        return false;
    }

    public boolean applicationOpenTempFile(NSApplication app, String filename) {
        log.debug("applicationOpenTempFile:" + filename);
        return this.applicationOpenFile(app, filename);
    }

    /**
     * @return true if the file was successfully opened, false otherwise.
     */
    public boolean applicationOpenUntitledFile(NSApplication app) {
        log.debug("applicationOpenUntitledFile");
        return this.newDocument() != null;
    }

    public boolean applicationShouldHandleReopen(NSApplication app, boolean visibleWindowsFound) {
        log.debug("applicationShouldHandleReopen");
        if (this.orderedBrowsers().count() == 0 && this.orderedTransfers().count() == 0) {
            return this.newDocument() == null;
        }
        return false;
    }

    public void applicationDidFinishLaunching(NSNotification notification) {
        Growl.instance().register();
        log.info("Running Java " + System.getProperty("java.version"));
        if (log.isInfoEnabled())
            log.info("Available localizations:" + NSBundle.mainBundle().localizations());
        if (Preferences.instance().getBoolean("queue.openByDefault")) {
            this.showTransferQueueClicked(null);
        }
        int uses = Preferences.instance().getInteger("uses");
        if (Preferences.instance().getBoolean("donate")) {
            if (!NSApplication.loadNibNamed("Donate", this)) {
                log.fatal("Couldn't load Donate.nib");
            }
            else {
                this.donationSheet.setTitle(this.donationSheet.title() + " (" + uses + ")");
                this.donationSheet.center();
                this.donationSheet.makeKeyAndOrderFront(null);
            }
        }
        if (Preferences.instance().getBoolean("update.check")) {
            this.checkForUpdate(false);
        }
        this.rendezvous.init();
    }

    public void applicationShouldSleep(Object o) {
        log.debug("applicationShouldSleep");
        //Stopping rendezvous service discovery
        this.rendezvous.quit();
        //halt all transfers
        CDQueueController.instance().stopAllButtonClicked(null);
        //close all browsing connections
        NSArray windows = NSApplication.sharedApplication().windows();
        int count = windows.count();
        // Determine if there are any open connections
        while (0 != count--) {
            NSWindow window = (NSWindow) windows.objectAtIndex(count);
            CDBrowserController controller = CDBrowserController.controllerForWindow(window);
            if (null != controller) {
                controller.unmount();
            }
        }
    }

    public void applicationShouldWake(Object o) {
        log.debug("applicationShouldWake");
        this.rendezvous.init();
    }

    public int applicationShouldTerminate(NSApplication app) {
        log.debug("applicationShouldTerminate");
        NSArray windows = app.windows();
        int count = windows.count();
        // Determine if there are any open connections
        while (0 != count--) {
            NSWindow window = (NSWindow) windows.objectAtIndex(count);
            CDBrowserController controller = CDBrowserController.controllerForWindow(window);
            if (null != controller) {
                if (controller.isConnected()) {
                    if (Preferences.instance().getBoolean("browser.confirmDisconnect")) {
                        int choice = NSAlertPanel.runAlert(NSBundle.localizedString("Quit", ""),
                                NSBundle.localizedString("You are connected to at least one remote site. Do you want to review open browsers?", ""),
                                NSBundle.localizedString("Review...", ""), //default
                                NSBundle.localizedString("Quit Anyway", ""), //alternate
                                NSBundle.localizedString("Cancel", "")); //other
                        if (choice == NSAlertPanel.AlternateReturn) {
                            // Quit
                            return CDQueueController.applicationShouldTerminate(app);
                        }
                        if (choice == NSAlertPanel.OtherReturn) {
                            // Cancel
                            return NSApplication.TerminateCancel;
                        }
                        if (choice == NSAlertPanel.DefaultReturn) {
                            // Review
                            // if at least one window reqested to terminate later, we shall wait
                            return CDBrowserController.applicationShouldTerminate(app);
                        }
                    }
                    else {
                        controller.unmount();
                    }
                }
            }
        }
        return CDQueueController.applicationShouldTerminate(app);
    }

    public void applicationWillTerminate(NSNotification notification) {
        log.debug("applicationWillTerminate");
        NSNotificationCenter.defaultCenter().removeObserver(this);
        //stoppping worker thread
        //this.threadWorkerTimer.invalidate();
        //Terminating rendezvous discovery
        this.rendezvous.quit();
        //Writing major info
        this.saveVersionInfo();
        //Writing usage info
        Preferences.instance().setProperty("uses", Preferences.instance().getInteger("uses") + 1);
        Preferences.instance().save();
    }

    public CDBrowserController newDocument() {
        return this.newDocument(false);
    }

    public CDBrowserController newDocument(boolean force) {
        log.debug("newDocument");
        NSArray browsers = this.orderedBrowsers();
        if (!force) {
            java.util.Enumeration enumerator = browsers.objectEnumerator();
            while (enumerator.hasMoreElements()) {
                CDBrowserController controller = (CDBrowserController) enumerator.nextElement();
                if (!controller.hasSession()) {
                    controller.window().makeKeyAndOrderFront(null);
                    return controller;
                }
            }
        }
        CDBrowserController controller = new CDBrowserController();
        if (browsers.count() > 0) {
            controller.cascade();
        }
        controller.window().makeKeyAndOrderFront(null);
        return controller;
    }

    // ----------------------------------------------------------
    // Applescriptability
    // ----------------------------------------------------------

    public boolean applicationDelegateHandlesKey(NSApplication application, String key) {
        log.debug("applicationDelegateHandlesKey:" + key);
        return key.equals("orderedBrowsers");
    }

    public NSArray orderedTransfers() {
        log.debug("orderedTransfers");
        NSApplication app = NSApplication.sharedApplication();
        NSArray orderedWindows = (NSArray) NSKeyValue.valueForKey(app, "orderedWindows");
        int i, c = orderedWindows.count();
        NSMutableArray orderedDocs = new NSMutableArray();
        Object curDelegate;
        for (i = 0; i < c; i++) {
            if (((NSWindow) orderedWindows.objectAtIndex(i)).isVisible()) {
                curDelegate = ((NSWindow) orderedWindows.objectAtIndex(i)).delegate();
                if ((curDelegate != null) && (curDelegate instanceof CDQueueController)) {
                    orderedDocs.addObject(curDelegate);
                    log.debug("orderedTransfers:" + orderedDocs);
                    return orderedDocs;
                }
            }
        }
        log.debug("orderedTransfers:" + orderedDocs);
        return orderedDocs;
    }

    public NSArray orderedBrowsers() {
        log.debug("orderedBrowsers");
        NSApplication app = NSApplication.sharedApplication();
        NSArray orderedWindows = (NSArray) NSKeyValue.valueForKey(app, "orderedWindows");
        int i, c = orderedWindows.count();
        NSMutableArray orderedDocs = new NSMutableArray();
        Object curDelegate;
        for (i = 0; i < c; i++) {
            if (((NSWindow) orderedWindows.objectAtIndex(i)).isVisible()) {
                curDelegate = ((NSWindow) orderedWindows.objectAtIndex(i)).delegate();
                if ((curDelegate != null) && (curDelegate instanceof CDBrowserController)) {
                    orderedDocs.addObject(curDelegate);
                }
            }
        }
        return orderedDocs;
    }

    // ----------------------------------------------------------

//	private String readVersionInfo() {
//		if(VERSION_FILE.exists()) {
//			NSData plistData = new NSData(VERSION_FILE);
//			String[] errorString = new String[]{null};
//			Object propertyListFromXMLData =
//			    NSPropertyListSerialization.propertyListFromData(plistData,
//			        NSPropertyListSerialization.PropertyListImmutable,
//			        new int[]{NSPropertyListSerialization.PropertyListXMLFormat},
//			        errorString);
//			if(errorString[0] != null) {
//				log.error("Problem reading version file: "+errorString[0]);
//			}
//			else {
//				log.debug("Successfully read version info: "+propertyListFromXMLData);
//			}
//			if(propertyListFromXMLData instanceof NSDictionary) {
//				NSDictionary dict = (NSDictionary)propertyListFromXMLData;
//				return (String)dict.objectForKey("Version");
//			}
//		}
//		return null;
//	}

    private void saveVersionInfo() {
        try {
            NSMutableDictionary dict = new NSMutableDictionary();
            dict.setObjectForKey(NSBundle.mainBundle().objectForInfoDictionaryKey("CFBundleVersion"), "Version");
            NSMutableData collection = new NSMutableData();
            String[] errorString = new String[]{null};
            collection.appendData(NSPropertyListSerialization.dataFromPropertyList(dict,
                    NSPropertyListSerialization.PropertyListXMLFormat,
                    errorString));
            if (errorString[0] != null) {
                log.error("Problem writing version file: " + errorString[0]);
            }
            if (collection.writeToURL(VERSION_FILE.toURL(), true)) {
                log.info("Version file sucessfully saved to :" + VERSION_FILE.toString());
            }
            else {
                log.error("Error saving version file to :" + VERSION_FILE.toString());
            }
        }
        catch (java.net.MalformedURLException e) {
            log.error(e.getMessage());
        }
    }

    public boolean applicationShouldTerminateAfterLastWindowClosed(NSApplication app) {
        return false;
    }
}
