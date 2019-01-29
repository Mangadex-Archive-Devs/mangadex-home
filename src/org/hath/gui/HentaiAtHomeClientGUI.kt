/*

Copyright 2008-2016 E-Hentai.org
https://forums.e-hentai.org/
ehentai@gmail.com

This file is part of Hentai@Home GUI.

Hentai@Home GUI is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Hentai@Home GUI is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Hentai@Home GUI.  If not, see <http://www.gnu.org/licenses/>.

*/

package org.hath.gui

import org.hath.base.*

import java.awt.*
import java.awt.event.*
import javax.swing.*

class HentaiAtHomeClientGUI(args: Array<String>) : JFrame(), HathGUI, ActionListener, WindowListener, MouseListener, Runnable {
    private val client: HentaiAtHomeClient?
    private val controlPane: HHControlPane
    private val logPane: HHLogPane
    private val myThread: Thread
    private val refresh_settings: JMenuItem
    private val suspend_resume: JMenuItem
    private val suspend_5min: JMenuItem
    private val suspend_15min: JMenuItem
    private val suspend_30min: JMenuItem
    private val suspend_1hr: JMenuItem
    private val suspend_2hr: JMenuItem
    private val suspend_4hr: JMenuItem
    private val suspend_8hr: JMenuItem
    private var tray: SystemTray? = null
    private var trayIcon: TrayIcon? = null
    private var trayFirstMinimize: Boolean = false
    private var lastSettingRefresh: Long = 0

    init {
        val mainjar = "HentaiAtHome.jar"
        if (!java.io.File(mainjar).canRead()) {
            Out.error("Required JAR file $mainjar could not be found. Please re-download Hentai@Home.")
            System.exit(-1)
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (e: Exception) {
            e.printStackTrace()
            System.exit(-1)
        }

        val icon16 = Toolkit.getDefaultToolkit().getImage(javaClass.getResource("/src/org/hath/gui/icon16.png"))
        val icon32 = Toolkit.getDefaultToolkit().getImage(javaClass.getResource("/src/org/hath/gui/icon32.png"))

        title = "Hentai@Home " + Settings.CLIENT_VERSION + " (Build " + Settings.CLIENT_BUILD + ")"
        iconImage = icon32
        setSize(1000, 550)
        isResizable = true

        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        addWindowListener(this)

        // set up the menu bar

        val mb = JMenuBar()
        val program = JMenu("Program")
        val suspend = JMenu("Suspend")

        // set up the program menu
        refresh_settings = JMenuItem("Refresh Settings")
        refresh_settings.addActionListener(this)
        refresh_settings.isEnabled = false
        program.add(refresh_settings)
        program.add(JSeparator())
        val program_exit = JMenuItem("Shutdown H@H")
        program_exit.addActionListener(this)
        program.add(program_exit)

        // set up the suspend menu
        suspend_resume = JMenuItem("Resume")
        suspend_resume.isEnabled = false
        suspend_5min = JMenuItem("Suspend for 5 Minutes")
        suspend_15min = JMenuItem("Suspend for 15 Minutes")
        suspend_30min = JMenuItem("Suspend for 30 Minutes")
        suspend_1hr = JMenuItem("Suspend for 1 Hour")
        suspend_2hr = JMenuItem("Suspend for 2 Hours")
        suspend_4hr = JMenuItem("Suspend for 4 Hours")
        suspend_8hr = JMenuItem("Suspend for 8 Hours")

        suspend_resume.addActionListener(this)
        suspend_5min.addActionListener(this)
        suspend_15min.addActionListener(this)
        suspend_30min.addActionListener(this)
        suspend_1hr.addActionListener(this)
        suspend_2hr.addActionListener(this)
        suspend_4hr.addActionListener(this)
        suspend_8hr.addActionListener(this)
        suspend.add(suspend_resume)
        suspend.add(JSeparator())
        suspend.add(suspend_5min)
        suspend.add(suspend_15min)
        suspend.add(suspend_30min)
        suspend.add(suspend_1hr)
        suspend.add(suspend_2hr)
        suspend.add(suspend_4hr)

        setResumeEnabled(false)
        setSuspendEnabled(false)

        mb.add(program)
        mb.add(suspend)
        jMenuBar = mb

        // initialize the panes

        contentPane.layout = BorderLayout()

        controlPane = HHControlPane(this)
        contentPane.add(controlPane, BorderLayout.PAGE_START)

        logPane = HHLogPane()
        contentPane.add(logPane, BorderLayout.CENTER)

        // create the systray

        if (SystemTray.isSupported()) {
            trayFirstMinimize = true // popup the "still running" box the first time the client is minimized to the systray this run
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE // we'll handle this with the WindowListener

            tray = SystemTray.getSystemTray()
            val trayMenu = PopupMenu()

            //MenuItem test = new MenuItem("test");
            //test.addActionListener(this);
            //trayMenu.add(test);

            trayIcon = TrayIcon(icon16, "Hentai@Home", trayMenu)
            trayIcon!!.addMouseListener(this)

            try {
                tray!!.add(trayIcon!!)
            } catch (e: AWTException) {
                e.printStackTrace()
            }

        }

        var startVisible = true

        for (s in args) {
            if (s.equals("--silentstart", ignoreCase = true)) {
                if (SystemTray.isSupported()) {
                    startVisible = false
                }
            }
        }

        pack()
        isVisible = startVisible

        lastSettingRefresh = System.currentTimeMillis()

        myThread = Thread(this)
        myThread.start()

        try {
            Thread.currentThread().sleep((if (startVisible) 2000 else 60000).toLong())
        } catch (e: Exception) {
        }

        Settings.activeGUI = this
        Stats.trackBytesSentHistory()
        client = HentaiAtHomeClient(InputQueryHandlerGUI(this), args)
        setSuspendEnabled(true)
    }

    override fun run() {
        while (true) {
            try {
                Thread.sleep(500)
            } catch (e: Exception) {
            }

            if (!Stats.isClientSuspended && suspend_resume.isEnabled) {
                setResumeEnabled(false)
                setSuspendEnabled(true)
            }

            if (!refresh_settings.isEnabled && lastSettingRefresh < System.currentTimeMillis() - 60000) {
                refresh_settings.isEnabled = true
            }

            controlPane.updateData()
            logPane.checkRebuildLogDisplay()
        }
    }

    override fun notifyWarning(title: String, text: String) {
        JOptionPane.showMessageDialog(this, text, title, JOptionPane.WARNING_MESSAGE)
    }

    override fun notifyError(reason: String) {
        JOptionPane.showMessageDialog(this, "$reason\n\nFor more information, look in the log files found in the data directory.", "Hentai@Home has encountered an error", JOptionPane.ERROR_MESSAGE)
    }

    // ActionListener for the JMenuBar
    override fun actionPerformed(e: ActionEvent) {
        val cmd = e.actionCommand

        if (cmd == "Refresh Settings") {
            refresh_settings.isEnabled = false
            client!!.clientAPI!!.refreshSettings()
        } else if (cmd == "Shutdown H@H") {
            if (client != null) {
                GUIThreaded(client, GUIThreaded.ACTION_SHUTDOWN)
            } else {
                System.exit(0)
            }
        } else if (cmd == "Resume") {
            clientResume()
        } else if (cmd == "Suspend for 5 Minutes") {
            clientSuspend(60 * 5)
        } else if (cmd == "Suspend for 15 Minutes") {
            clientSuspend(60 * 15)
        } else if (cmd == "Suspend for 30 Minutes") {
            clientSuspend(60 * 30)
        } else if (cmd == "Suspend for 1 Hour") {
            clientSuspend(60 * 60)
        } else if (cmd == "Suspend for 2 Hours") {
            clientSuspend(60 * 120)
        } else if (cmd == "Suspend for 4 Hours") {
            clientSuspend(60 * 240)
        } else if (cmd == "Suspend for 8 Hours") {
            clientSuspend(60 * 480)
        }
    }

    // WindowListener for the JFrame
    override fun windowActivated(e: WindowEvent) {}

    override fun windowClosed(e: WindowEvent) {}

    override fun windowClosing(e: WindowEvent) {
        isVisible = false

        if (trayFirstMinimize) {
            trayFirstMinimize = false
            trayIcon!!.displayMessage("Hentai@Home is still running", "Click here when you wish to show the Hentai@Home Client", TrayIcon.MessageType.INFO)
        }
    }

    override fun windowDeactivated(e: WindowEvent) {}
    override fun windowDeiconified(e: WindowEvent) {}
    override fun windowIconified(e: WindowEvent) {}
    override fun windowOpened(e: WindowEvent) {}


    // MouseListener for the SystemTray
    override fun mouseClicked(e: MouseEvent) {
        isVisible = true
    }

    override fun mouseEntered(e: MouseEvent) {}
    override fun mouseExited(e: MouseEvent) {}
    override fun mousePressed(e: MouseEvent) {}
    override fun mouseReleased(e: MouseEvent) {}


    private fun clientSuspend(suspendTimeSeconds: Int) {
        if (client != null && client.clientAPI != null) {
            if (client.clientAPI!!.clientSuspend(suspendTimeSeconds).resultText == "OK") {
                setSuspendEnabled(false)
                setResumeEnabled(true)
            } else {
                Out.error("Failed to suspend")
            }
        } else {
            Out.error("The client is not started, cannot suspend.")
        }
    }

    private fun clientResume() {
        if (client != null && client.clientAPI != null) {
            if (client.clientAPI!!.clientResume().resultText == "OK") {
                setSuspendEnabled(true)
                setResumeEnabled(false)
            } else {
                Out.error("Failed to resume")
            }
        } else {
            Out.error("The client is not started, cannot resume.")
        }
    }

    private fun setResumeEnabled(enabled: Boolean) {
        suspend_resume.isEnabled = enabled
    }

    private fun setSuspendEnabled(enabled: Boolean) {
        suspend_5min.isEnabled = enabled
        suspend_15min.isEnabled = enabled
        suspend_30min.isEnabled = enabled
        suspend_1hr.isEnabled = enabled
        suspend_2hr.isEnabled = enabled
        suspend_4hr.isEnabled = enabled
        suspend_8hr.isEnabled = enabled
    }

    companion object {

        @JvmStatic
        fun main(args: Array<String>) {
            HentaiAtHomeClientGUI(args)
        }
    }
}
