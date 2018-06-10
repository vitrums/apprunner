package net.vit.apprunner.util;

import java.util.stream.IntStream;

public class LCS {
  private final String x, y;
  private final int m, n;
  private int c[][];

  public LCS(String x, String y) {
    this.x = x;
    this.y = y;
    m = x.length();
    n = y.length();
  }

  public int computeLcsLength() {
    if (c == null) {
      c = new int[m + 1][n + 1];
      IntStream.range(0, m).forEach((i) -> c[i][0] = 0);
      IntStream.range(0, n).forEach((j) -> c[0][j] = 0);
      for (int i = 1; i <= m; ++i) {
        for (int j = 1; j <= n; ++j) {
          if (x.charAt(i - 1) == y.charAt(j - 1)) {
            c[i][j] = c[i - 1][j - 1] + 1;
          } else {
            c[i][j] = Math.max(c[i][j - 1], c[i - 1][j]);
          }
        }
      }
    }

    return c[m][n];
  }
}
