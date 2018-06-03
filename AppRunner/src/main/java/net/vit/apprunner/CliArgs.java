package net.vit.apprunner;

import java.util.ArrayList;
import java.util.List;
import com.beust.jcommander.Parameter;

/**
 * Class describes command line arguments. Each field annotated with {@link Parameter} becomes a
 * valid parameter. Jcommander lib takes care of all parsing.
 * 
 * @author vit
 */
public class CliArgs {
  @Parameter(names = {"--help", "-h"}, description = "Displays help", help = true)
  boolean help;

  @Parameter(names = {"--module", "-m"}, description = "Path to <your-module>.xml file. "
      + "It contains a list of tasks to perform on demand. See -t key", required = true)
  String module;

  @Parameter(names = {"--properties", "-p"}, variableArity = true,
      description = "<your-constants>.properties file. Each line has a form key=value. "
          + "Each (key,value) pair becomes a constant of top level "
          + "(i.e. under <configuration>/<constants> in module.xml file)")
  String properties;

  @Parameter(names = {"--tasks", "-t"},
      description = "List of tasks (must be specified in your module) to execute",
      variableArity = true, required = true)
  List<String> tasks = new ArrayList<>();
}
