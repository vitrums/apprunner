package net.vit.apprunner.util;

import java.io.File;

public class Util {
  public static final String CONFIG_DIR = "config";
  
  /** @return The current method name. */
  public static String getCurrentMethod() {
      return Thread.currentThread().getStackTrace()[2].getMethodName();
      //[0]=getStackTrace, [1]=getMethod, [2]=<method name we're looking for>
  }
  
  public static String correctFileSeparator(String path) {
    String result = path.replace("/", File.separator).replace("\\", File.separator);
    return result.replace("//", File.separator).replace("\\\\", File.separator);
  }
}
