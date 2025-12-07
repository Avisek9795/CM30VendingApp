package com.example.cm30vendingapp.util;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * LoggerHelper - safe logging to file for debug & release builds.
 * Client can share the log file for remote debugging.
 */
public class LoggerHelper {

    private static File logFile;

    /**
     * Initialize logger. Call once in Application or Service onCreate.
     */
    public static void init(Context context) {
        File dir = new File(context.getFilesDir(), "logs");
        if (!dir.exists()) dir.mkdirs();

        logFile = new File(dir, "vending_log.txt");
        writeLine("=== Logger initialized ===");
        writeLine("Device: " + Build.MANUFACTURER + " " + Build.MODEL + ", SDK " + Build.VERSION.SDK_INT);
    }

    /**
     * Log a message with optional exception
     */
    public static void log(String tag, String message, Exception e) {
        String fullMessage = tag + " | " + message;
        if (e != null) fullMessage += " | Exception: " + e.toString();
        writeLine(fullMessage);
    }

    public static void log(String tag, String message) {
        log(tag, message, null);
    }

    /**
     * Internal helper to append a line to the file
     */
    private static synchronized void writeLine(String text) {
        if (logFile == null) return;

        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(System.currentTimeMillis() + " | " + text + "\n");
        } catch (IOException io) {
            io.printStackTrace();
        }
    }

    /**
     * Optional: return the log file so client can send it
     */
    public static File getLogFile() {
        return logFile;
    }
}
