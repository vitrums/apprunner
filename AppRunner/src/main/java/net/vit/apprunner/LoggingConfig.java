package net.vit.apprunner;

import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;
import net.vit.apprunner.util.DebugLoggingFormatter;
import net.vit.apprunner.util.SysoutLoggingFormatter;

/**
 * Responsible for attaching handlers to logger(s) and cleaning up.
 * 
 * @author vit
 */
public class LoggingConfig {
  static Logger logger = AppRunner.logger;

  private FileHandler logFileHandler;
  private StreamHandler logStdOutHandler;
  private StreamHandler debugHandler;

  /**
   * Activates simple file handler and stdout handler.
   * 
   * @throws IOException
   */
  void ensureLogging() throws IOException {
    // Disable default handler
    logger.setUseParentHandlers(false);
    
    if (logFileHandler == null) {
      logFileHandler = new FileHandler("apprunner_log.txt", true);
      // String.format(format, date, source, logger, level, message, thrown);
      logFileHandler.setFormatter(new SimpleFormatter());
      // PUBLISH this level
      logFileHandler.setLevel(Level.INFO);
      logger.addHandler(logFileHandler);
    }

    if (logStdOutHandler == null && !AppRunner.isDebug) {
      logStdOutHandler = new StreamHandler(System.out, new SysoutLoggingFormatter());
      logStdOutHandler.setLevel(Level.INFO);
      logger.addHandler(logStdOutHandler);
    }

    if (debugHandler == null && AppRunner.isDebug) {
      debugHandler = new ConsoleHandler() {
        @Override
        protected void setOutputStream(final OutputStream out) throws SecurityException {
          super.setOutputStream(System.out);
        }
      };
      debugHandler.setFormatter(new DebugLoggingFormatter());
      debugHandler.setLevel(Level.ALL);
      logger.addHandler(debugHandler);
    }
  }

  /**
   * Closes IO.
   */
  void cleanup() {
    if (logFileHandler != null) {
      logFileHandler.flush();
      logFileHandler.close();
      logger.removeHandler(logFileHandler);
      logFileHandler = null;
    }

    if (logStdOutHandler != null) {
      logStdOutHandler.flush();
      logStdOutHandler.close();
      logger.removeHandler(logStdOutHandler);
      logStdOutHandler = null;
    }

    if (debugHandler != null) {
      debugHandler.flush();
      debugHandler.close();
      logger.removeHandler(debugHandler);
      debugHandler = null;
    }
  }
}
