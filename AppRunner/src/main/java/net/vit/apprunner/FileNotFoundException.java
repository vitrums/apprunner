package net.vit.apprunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.vit.apprunner.util.LCS;

@SuppressWarnings("serial")
public class FileNotFoundException extends Exception {
  static class Option implements Comparable<Option> {
    private final String file;
    private final int lcsLength;

    Option(String file, int lcsLength) {
      this.file = file;
      this.lcsLength = lcsLength;
    }

    @Override
    public int compareTo(Option o) {
      return Integer.compare(this.lcsLength, o.lcsLength);
    }

    public int reverseCompareTo(Option o) {
      return -compareTo(o);
    }
  }

  private final String file;
  private final List<String> filesInDir;
  private Optional<String> optionsHelp;

  public FileNotFoundException(String initialMessage, String file, List<String> filesInDir) {
    super(initialMessage);
    this.file = file;
    this.filesInDir = filesInDir;
    optionsHelp = Optional.empty();
  }

  public FileNotFoundException(String initialMessage) {
    this(initialMessage, null, null);
  }
  
  public Optional<String> getOptionsHelp() {
    if (!optionsHelp.isPresent() && filesInDir.size() > 0) {
      List<Option> options =
          filesInDir.stream().map((s) -> new Option(s, new LCS(file, s).computeLcsLength()))
              .filter((o) -> o.lcsLength > 0).sorted(Option::reverseCompareTo)
              .collect(Collectors.toList());
      StringBuilder errorMessage = new StringBuilder("Closest matches found:\n");
      IntStream.range(0, Math.min(5, options.size())).forEach((i) -> {
        Option o = options.get(i);
        errorMessage.append(String.format("[%d]: %s%n", i, o.file));
//        errorMessage.append(String.format("[%d]: %s with LCS=%d%n", i, o.file, o.lcsLength));
      });
      
      optionsHelp = Optional.of(errorMessage.toString());
    }
    
    return optionsHelp;
  }
}
