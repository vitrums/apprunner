package net.vit.apprunner.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * Logger formatter to display information in std out.
 * 
 * @author vit
 */
public class SysoutLoggingFormatter extends SimpleFormatter {
  private static final String format = "[%1$tT] %4$-7s: %5$s%6$s%n";
  private final Date dat = new Date();

  public synchronized String format(LogRecord record) {
    dat.setTime(record.getMillis());
    String source;
    if (record.getSourceClassName() != null) {
      // source = record.getSourceClassName();
      String fullClassName = record.getSourceClassName();
      int classNameIdx = fullClassName.lastIndexOf(".");
      source = classNameIdx == -1 ? fullClassName : fullClassName.substring(classNameIdx + 1);
      if (record.getSourceMethodName() != null) {
        source += "." + record.getSourceMethodName() + "()";
      }
    } else {
      source = record.getLoggerName();
    }
    String message = formatMessage(record);
    String throwable = "";
    if (record.getThrown() != null) {
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      pw.println();
      record.getThrown().printStackTrace(pw);
      pw.close();
      throwable = sw.toString();
    }
    return String.format(format, dat, source, record.getLoggerName(), record.getLevel(), message,
        throwable);
  }
}
