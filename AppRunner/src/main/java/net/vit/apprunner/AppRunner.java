package net.vit.apprunner;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jdom2.JDOMException;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import net.vit.apprunner.NameReferenceResolver.Scope;
import net.vit.apprunner.Settings.*;
import net.vit.apprunner.util.DebugLoggingFormatter;
import net.vit.apprunner.util.Util;

/**
 * Performs actions requested by user in command line. The list of CLI keys can be found in
 * {@link CliArgs}.
 * 
 * @author vit
 * @see #launch()
 */
public class AppRunner {
  static Logger logger = null;

  static {
    // Programmatic approach would be to use static initializer. Other two options are tweaking the
    // properties file and supplying explicitly the property as a VM arg.
    System.setProperty("java.util.logging.SimpleFormatter.format",
        "[%1$tF %1$tT] %4$-7s: %5$s%6$s%n");
    logger = Logger.getLogger(AppRunner.class.getName());
  }

  private final String[] argv;
  private FileHandler logFileHandler;
  private StreamHandler logStdOutHandler;
  private StreamHandler debugHandler;
  private CliArgs cliArgs;
  private Settings settings;

  public AppRunner(String[] argv) {
    this.argv = argv;
    logger.setLevel(Level.ALL);
  }

  /**
   * Main method. Does the following actions in sequence:
   * <ul>
   * <li>Parses CLI arguments.</li>
   * <li>Parses {@literal <module>.xml}.</li>
   * <li>Resolves all required for this launch name references within this and parent modules.</li>
   * <li>Finally performes all the tasks requested by user.</li>
   * </ul>
   */
  public void launch() {
    try {
      // Setup logger and handlers
      ensureLogging();
      // Exit fast if just usage was asked for
      if (!parseCliArgs()) {
        return;
      }
      // Getting config
      parseXmlModules();
      // Resolving all names for specified tasks
      resolveNames();
      // Do work
      applyConfig();
    } catch (Exception e) {
      System.out.println("An error has occurred. Full stack trace:");
      e.printStackTrace();
    } finally {
      cleanup();
    }
  }

  /**
   * Parses CLI. Fails fast upon CLI syntax error with a {@link ParameterException} being thrown
   * before any action was taken.
   * 
   * @throws ParameterException CLI syntax error
   * @return false if user only wants usage information
   */
  private boolean parseCliArgs() throws ParameterException {
    if (logStdOutHandler == null || logFileHandler == null || debugHandler == null) {
      throw new IllegalStateException(
          "Logger was not found. Did you forget to call ensureLogging()?");
    }

    logger.finer(String.format("Parsing CLI args: %s.", Arrays.asList(argv)));
    cliArgs = new CliArgs();
    JCommander jcommander = JCommander.newBuilder().addObject(cliArgs).build();
    jcommander.parse(argv);

    if (cliArgs.help) {
      jcommander.usage();
      return false;
    }

    return true;
  }

  /**
   * Parses {@literal <user-specified-module>.xml} file. Fails fast upon XSD scheme error with a
   * {@link JDOMException} being thrown before any action was taken. Goes recursievely through all
   * ascendant modules of the module specified in the command line.
   * 
   * @throws JDOMException
   * @throws IOException
   */
  private void parseXmlModules() throws JDOMException, IOException {
    XmlParser xmlParser = new XmlParser(cliArgs);
    settings = xmlParser.parseModuleXml();
  }

  /**
   * Loads properties file, populates constants information from it. Then resolves constant
   * references inside every task requested for an execution.
   * <p/>
   * It will not attempt to resolve any other information, that would not be used for this
   * particular launch, such as the entities defined within remaining tasks of the specified module,
   * which were not mentioned as the command line arguments.
   * 
   * @throws IOException
   */
  private void resolveNames() throws IOException {
    try (InputStream inStream = new FileInputStream(Util.CONFIG_DIR + cliArgs.properties)) {
      Properties properties = new Properties();
      properties.load(inStream);
      properties.forEach((name, value) -> settings.getConfiguration()
          .putConstant(String.valueOf(name), String.valueOf(value)));

      NameReferenceResolver resolver = new NameReferenceResolver(settings);

      cliArgs.tasks.forEach((taskName) -> {
        Task task = settings.getTasks().get(taskName);
        if (task == null) {
          String errorMessage =
              String.format("Task \"%s\" wasn't found in the module \"%s\" or inherited modules.",
                  taskName, cliArgs.module);
          logger.severe(errorMessage);
          throw new IllegalArgumentException(errorMessage);
        }

        logger.finer(
            String.format("Task \"%s\" was found. Proceeding with names resolution.", taskName));

        Scope scope = Scope.of(taskName, Scope.GLOBAL);

        task.getActions().stream().filter(Task.Application.class::isInstance)
            .map(Task.Application.class::cast)
            .forEach((application) -> application.resolveNames(resolver, scope));

        task.getActions().stream().filter(Task.OperationRef.class::isInstance)
            .map(Task.OperationRef.class::cast).forEach((operationRef) -> {
              operationRef.resolveNames(resolver, scope);

              Configuration.OperationDef operationDef =
                  settings.getConfiguration().getOperationDefs().get(operationRef.getRef());
              if (operationDef == null) {
                String errorMessage = String.format("There is no such \"%s\" operation defined.",
                    operationRef.getRef());
                logger.severe(errorMessage);
                throw new IllegalArgumentException(errorMessage);
              }

              operationDef.getOperation().resolveNames(resolver, scope);
            });

        task.getActions().stream().filter(Task.Operation.class::isInstance)
            .map(Task.Operation.class::cast)
            .forEach((operation) -> operation.resolveNames(resolver, scope));
      });
    }
  }

  /**
   * Executes tasks specified by user.
   */
  private void applyConfig() {
    /**
     * For convenience contains methods which perform the tasks, requested by user. They can launch
     * external processes, perform operations on files etc.
     */
    class TaskExecuteHelper {
      TaskExecuteHelper() {}

      /**
       * For a given {@link FileName} initiates a search inside {@link FileName#getIn()} directory.
       * 
       * @param fileName file to search
       * @return string path of the file
       */
      private Path searchFile(FileName fileName) {
        class MutableBoolean {
          boolean value;

          MutableBoolean(boolean value) {
            this.value = value;
          }
        }

        List<Path> files = null;
        try (Stream<Path> stream = Files.walk(Paths.get(fileName.getIn()), 1)) {
          files = stream.filter((path) -> {
            // System.out.printf("path.getFileName().toString()=%s%n",
            // path.getFileName().toString());
            MutableBoolean matches = new MutableBoolean(true);
            String fileNameStr = path.getFileName().toString().toLowerCase();
            fileName.getStartsWith()
                .ifPresent((val) -> matches.value &= fileNameStr.startsWith(val.toLowerCase()));
            fileName.getEndsWith()
                .ifPresent((val) -> matches.value &= fileNameStr.endsWith(val.toLowerCase()));
            fileName.getContains()
                .ifPresent((val) -> matches.value &= fileNameStr.contains(val.toLowerCase()));
            return matches.value;
          }).sorted().collect(Collectors.toList());
        } catch (IOException e) {
          logger.throwing(AppRunner.class.getName(), Util.getCurrentMethod(), e);
          throw new RuntimeException(e);
        }

        if (files == null || files.size() == 0) {
          String errorMessage = String.format(
              "Couldn't find any file in \"%s\" such that starts with \"%s\", contains \"%s\" and ends with \"%s\"",
              fileName.getIn(), fileName.getStartsWith().orElse(""),
              fileName.getContains().orElse(""), fileName.getEndsWith().orElse(""));
          logger.severe(errorMessage);
          throw new IllegalArgumentException(errorMessage);
        }

        return files.get(0);
      }

      /**
       * Runs the given application.
       * 
       * @param application
       */
      void launchApplication(Task.Application application) {
        for (List<Task.Application.ApplicationInput> execute : application.getExecuteList()) {
          List<String> command = new ArrayList<>();
          command.add(application.getExecutablePath());

          for (Task.Application.ApplicationInput input : execute) {
            if (input instanceof FileName) {
              command.add((searchFile((FileName) input)).toString());
            } else if (input instanceof Task.Application.StringArg) {
              command.add(((Task.Application.StringArg) input).getValue());
            }
          }

          try {
            String processInfo = String.format("Running [%s]. Output is:",
                command.stream().collect(Collectors.joining(" ")));
            System.out.println(processInfo);
            logger.finer(processInfo);
            Process process = new ProcessBuilder(command).start();

            BufferedReader stdOutReader =
                new BufferedReader(new InputStreamReader(process.getInputStream())),
                stdErrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            Optional<String> lineStdOut = Optional.empty(), lineStdErr = Optional.empty();

            do {
              lineStdOut.ifPresent(System.out::println);
              lineStdErr.ifPresent(System.err::println);
              lineStdOut = Optional.ofNullable(stdOutReader.readLine());
              lineStdErr = Optional.ofNullable(stdErrReader.readLine());
            } while (lineStdOut.isPresent() || lineStdErr.isPresent());
          } catch (IOException e) {
            logger.throwing(AppRunner.class.getName(), Util.getCurrentMethod(), e);
            throw new RuntimeException(e);
          }
        }
      }

      /**
       * Performs the given operation.
       * 
       * @param operation
       */
      void launchOperation(Task.Operation operation) {
        try {
          for (Task.Operation.InternalOp internalOp : operation.getInternals()) {
            if (internalOp instanceof Task.Operation.Rename) {
              Task.Operation.Rename rename = (Task.Operation.Rename) internalOp;
              Path filePath = searchFile(rename.getFileName());
              for (Task.Operation.Rename.RenameOption renameOption : rename.getRenameOptions()) {
                if (renameOption instanceof Task.Operation.Rename.ReplaceAll) {
                  Task.Operation.Rename.ReplaceAll replaceAll =
                      (Task.Operation.Rename.ReplaceAll) renameOption;
                  String fileName = filePath.getFileName().toString();
                  String newFileName =
                      fileName.replaceAll(replaceAll.getSubstring(), replaceAll.getWith());
                  Path newFilePath = filePath.resolveSibling(newFileName);
                  Files.move(filePath, newFilePath, StandardCopyOption.REPLACE_EXISTING);
                } else {
                  // We should never be here
                  String errorMessage =
                      String.format("Program failure. RenameOption has an unknown final type %s.",
                          renameOption.getClass().getName());
                  logger.severe(errorMessage);
                  throw new AssertionError(errorMessage);
                }
              }
            } else if (internalOp instanceof Task.Operation.Move || internalOp instanceof Task.Operation.Copy) {
              Task.Operation.MoveOrCopy moveOrCopy = (Task.Operation.MoveOrCopy) internalOp;
              Path toDirPath = Paths.get(moveOrCopy.getTo());
              Files.createDirectories(toDirPath);
              for (FileName fileName : moveOrCopy.getFileNames()) {
                Path filePath = searchFile(fileName);
                Path newFilePath = toDirPath.resolve(filePath.getFileName());
                if (internalOp instanceof Task.Operation.Move) {
                  Files.move(filePath, newFilePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                  Files.copy(filePath, newFilePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
              }
            } else if (internalOp instanceof Task.Operation.Delete) {
              Task.Operation.Delete delete = (Task.Operation.Delete) internalOp;
              for (FileName fileName : delete.getFileNames()) {
                Path filePath = searchFile(fileName);
                Files.delete(filePath);
              }
            } else {
              // We should never be here
              String errorMessage =
                  String.format("Program failure. InternalOp has an unknown final type %s.",
                      internalOp.getClass().getName());
              logger.severe(errorMessage);
              throw new AssertionError(errorMessage);
            }
          }
        } catch (IOException e) {
          logger.throwing(AppRunner.class.getName(), Util.getCurrentMethod(), e);
          throw new RuntimeException(e);
        }
      }
    }

    // For all tasks specified in command line by user
    cliArgs.tasks.stream().map((taskName) -> settings.getTasks().get(taskName)).forEach((task) -> {
      TaskExecuteHelper helper = new TaskExecuteHelper();
      for (Task.Action action : task.getActions()) {
        if (action instanceof Task.Application) {
          Task.Application application = (Task.Application) action;
          helper.launchApplication(application);
        } else if (action instanceof Task.OperationRef) {
          Task.OperationRef operationRef = (Task.OperationRef) action;
          Task.Operation operation = settings.getConfiguration().getOperationDefs()
              .get(operationRef.getRef()).getOperation();
          helper.launchOperation(operation);
        } else if (action instanceof Task.Operation) {
          Task.Operation operation = (Task.Operation) action;
          helper.launchOperation(operation);
        } else {
          // We should never be here
          String errorMessage = String.format(
              "Program failure. Action has an unknown final type %s.", action.getClass().getName());
          logger.severe(errorMessage);
          throw new AssertionError(errorMessage);
        }
      }
    });
  }

  /**
   * Activates simple file handler and stdout handler.
   * 
   * @throws IOException
   */
  private void ensureLogging() throws IOException {
    if (logFileHandler == null) {
      logFileHandler = new FileHandler("apprunner_log.txt", true);
      // String.format(format, date, source, logger, level, message, thrown);
      logFileHandler.setFormatter(new SimpleFormatter());
      // PUBLISH this level
      logFileHandler.setLevel(Level.CONFIG);
      logger.addHandler(logFileHandler);
    }

    if (logStdOutHandler == null) {
      logStdOutHandler = new StreamHandler(System.out, new SimpleFormatter());
      logStdOutHandler.setLevel(Level.INFO);
      logger.addHandler(logStdOutHandler);
    }

    if (debugHandler == null) {
      debugHandler = new ConsoleHandler() {
        @Override
        protected void setOutputStream(final OutputStream out) throws SecurityException {
          super.setOutputStream(System.out);
        }
      };
      debugHandler.setFormatter(new DebugLoggingFormatter());
      debugHandler.setLevel(Level.FINER);
      logger.addHandler(debugHandler);
    }
  }

  /**
   * Closes IO.
   */
  private void cleanup() {
    logger.finer("cleanup");
    if (logFileHandler != null) {
      logFileHandler.flush();
      logFileHandler.close();
      logFileHandler = null;
    }

    if (logStdOutHandler != null) {
      logStdOutHandler.flush();
      logStdOutHandler.close();
      logStdOutHandler = null;
    }

    if (debugHandler != null) {
      debugHandler.flush();
      debugHandler.close();
      debugHandler = null;
    }
  }
}
