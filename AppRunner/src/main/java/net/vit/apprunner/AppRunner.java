package net.vit.apprunner;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.*;
import static java.nio.file.StandardCopyOption.*;
import static java.nio.file.FileVisitResult.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jdom2.JDOMException;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import net.vit.apprunner.NameReferenceResolver.Scope;
import net.vit.apprunner.Settings.*;
import net.vit.apprunner.util.Util;

/**
 * Performs actions requested by user in command line. The list of CLI keys can be found in
 * {@link CliArgs}.
 * 
 * @author vit
 * @see #launch()
 */
public class AppRunner {
  static final boolean isDebug = false;
  static Logger logger = null;

  static {
    // Programmatic approach would be to use static initializer. Other two options are tweaking the
    // properties file and supplying explicitly the property as a VM arg.
    System.setProperty("java.util.logging.SimpleFormatter.format",
        "[%1$tF %1$tT] %4$-7s: %5$s%6$s%n");
    logger = Logger.getLogger(AppRunner.class.getName());
  }

  private final String[] argv;
  private LoggingConfig loggingConfig;
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
    boolean wasException = false;
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
      wasException = true;
      logger.log(Level.SEVERE, e.getMessage(), e);
      if (e.getCause() instanceof FileNotFoundException) {
        FileNotFoundException fnfe = (FileNotFoundException) e.getCause();
        fnfe.getOptionsHelp().ifPresent((msg) -> logger.severe(msg));
      }
      // e.printStackTrace();
    } finally {
      if (!wasException) {
        logger.info("All tasks completed.");
      }
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
    if (loggingConfig == null) {
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
    try (InputStream inStream = new FileInputStream(
        Util.correctFileSeparator(Util.CONFIG_DIR + File.separator + cliArgs.properties))) {
      Properties properties = new Properties();
      properties.load(inStream);
      properties.forEach((name, value) -> {
        if (!"".equals(String.valueOf(value).replace(" ", ""))) {
          settings.getConfiguration().putConstant(String.valueOf(name), String.valueOf(value));
        }
      });

      NameReferenceResolver resolver = new NameReferenceResolver(settings);

      cliArgs.tasks.forEach((taskName) -> {
        Task task = settings.getTasks().get(taskName);
        if (task == null) {
          String errorMessage =
              String.format("Task \"%s\" wasn't found in the module \"%s\" or inherited modules.",
                  taskName, cliArgs.module);
          throw new IllegalArgumentException(errorMessage);
        }

        logger.finer(
            String.format("Task \"%s\" was found. Proceeding with names resolution.", taskName));

        Scope scope = Scope.of(taskName, Scope.GLOBAL);

        task.getActions().stream().filter(Task.Application.class::isInstance)
            .map(Task.Application.class::cast)
            .forEach((application) -> application.resolveNames(resolver, scope));

        task.getActions().stream().filter(Task.Operation.class::isInstance)
            .map(Task.Operation.class::cast)
            .forEach((operation) -> operation.resolveNames(resolver, scope));

        task.getActions().stream().filter(Task.OperationRef.class::isInstance)
            .map(Task.OperationRef.class::cast).forEach((operationRef) -> {
              operationRef.resolveNames(resolver, scope);

              Configuration.OperationDef operationDef =
                  settings.getConfiguration().getOperationDefs().get(operationRef.getRef());
              if (operationDef == null) {
                String errorMessage = String.format("There is no such \"%s\" operation defined.",
                    operationRef.getRef());
                throw new IllegalArgumentException(errorMessage);
              }

              operationDef.getOperation().resolveNames(resolver, scope);
            });
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
       * For a given {@link FileNameBase} initiates a search inside {@link FileNameBase#getIn()}
       * directory.
       * 
       * @param fileNameBase files to search
       * @return paths to found files
       */
      private List<Path> searchFiles(FileNameBase fileNameBase) throws FileNotFoundException {
        String startsWith = fileNameBase.getStartsWith().isPresent()
            ? fileNameBase.getStartsWith().get().toLowerCase()
            : "";
        String endsWith =
            fileNameBase.getEndsWith().isPresent() ? fileNameBase.getEndsWith().get().toLowerCase()
                : "";
        String contains =
            fileNameBase.getContains().isPresent() ? fileNameBase.getContains().get().toLowerCase()
                : "";

        try (Stream<Path> stream = Files.walk(Paths.get(fileNameBase.getIn()), 1)) {
          List<Path> result = stream.filter((path) -> {
            String s = path.getFileName().toString().toLowerCase();
            return s.startsWith(startsWith) && s.endsWith(endsWith) && s.contains(contains);
          }).sorted().collect(Collectors.toList());

          if (!result.isEmpty())
            return result;
        } catch (IOException e) {
          throw new RuntimeException(e);
        }

        List<String> filesInDir = null;
        try (Stream<Path> stream = Files.walk(Paths.get(fileNameBase.getIn()), 1)) {
          filesInDir = stream.map(Path::getFileName).map(String::valueOf).map(String::toLowerCase)
              .filter((s) -> {
                return s.startsWith(startsWith) && s.endsWith(endsWith);
              }).sorted().collect(Collectors.toList());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }

        String errorMessage = String.format(
            "Couldn't find any file in \"%s\" such that starts with \"%s\", contains \"%s\" and ends with \"%s\"",
            fileNameBase.getIn(), startsWith, contains, endsWith);
        FileNotFoundException x;
        if (fileNameBase.getContains().isPresent() && !filesInDir.isEmpty()) {
          x = new FileNotFoundException(errorMessage, fileNameBase.getContains().get(), filesInDir);
        } else {
          x = new FileNotFoundException(errorMessage);
        }

        throw x;
      }

      /**
       * For a given {@link FileName} initiates a search inside {@link FileName#getIn()} directory.
       * 
       * @param fileName file to search
       * @return path of the file
       */
      private Path searchFile(FileName fileName) throws FileNotFoundException {
        return searchFiles(fileName).get(0);
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
              try {
                command.add((searchFile((FileName) input)).toString());
              } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
              }
            } else if (input instanceof Task.Application.StringArg) {
              command.add(((Task.Application.StringArg) input).getValue());
            }
          }

          try {
            String processInfo = String.format("Running [%s]. Output is:",
                command.stream().collect(Collectors.joining(" ")));
            logger.info(processInfo);
            Process process = new ProcessBuilder(command).start();

            BufferedReader stdOutReader =
                new BufferedReader(new InputStreamReader(process.getInputStream())),
                stdErrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            Optional<String> lineStdOut = Optional.empty(), lineStdErr = Optional.empty();

            do {
              lineStdOut.ifPresent(logger::info);
              lineStdErr.ifPresent(logger::severe);
              lineStdOut = Optional.ofNullable(stdOutReader.readLine());
              lineStdErr = Optional.ofNullable(stdErrReader.readLine());
            } while (lineStdOut.isPresent() || lineStdErr.isPresent());
          } catch (IOException e) {
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
              List<Path> filePaths = searchFiles(rename.getFileNames());
              for (Path filePath : filePaths) {
                for (Task.Operation.Rename.RenameOption renameOption : rename.getRenameOptions()) {
                  if (renameOption instanceof Task.Operation.Rename.ReplaceAll) {
                    Task.Operation.Rename.ReplaceAll replaceAll =
                        (Task.Operation.Rename.ReplaceAll) renameOption;
                    String fileName = filePath.getFileName().toString();
                    String newFileName =
                        fileName.replaceAll(replaceAll.getSubstring(), replaceAll.getWith());
                    Path newFilePath = filePath.resolveSibling(newFileName);
                    logger
                        .info(String.format("Renaming: \"%s\" -> \"%s\".", fileName, newFileName));
                    Files.move(filePath, newFilePath, StandardCopyOption.REPLACE_EXISTING);
                  } else {
                    // We should never be here
                    String errorMessage =
                        String.format("Program failure. RenameOption has an unknown final type %s.",
                            renameOption.getClass().getName());
                    throw new AssertionError(errorMessage);
                  }
                }
              }
            } else if (internalOp instanceof Task.Operation.Move
                || internalOp instanceof Task.Operation.Copy) {
              Task.Operation.MoveOrCopy moveOrCopyOp = (Task.Operation.MoveOrCopy) internalOp;
              final boolean move = internalOp instanceof Task.Operation.Move;
              Path toDirPath = Paths.get(moveOrCopyOp.getTo());
              Files.createDirectories(toDirPath);
              for (FileNameBase fileNameBase : moveOrCopyOp.getFileNames()) {
                List<Path> filePaths = new ArrayList<>();
                if (fileNameBase instanceof FileName) {
                  filePaths.add(searchFile((FileName) fileNameBase));
                } else {
                  filePaths = searchFiles((FileNames) fileNameBase);
                }

                class TreeCopier implements FileVisitor<Path> {
                  private final Path source;
                  private final Path target;

                  TreeCopier(Path source, Path target) {
                    this.source = source;
                    this.target = target;
                  }

                  @Override
                  public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                      throws IOException {
                    Path newdir = target.resolve(source.relativize(dir));
                    logger.finest(String.format("[dir]=%s [newdir]=%s", dir, newdir));
                    try {
                      Files.copy(dir, newdir);
                    } catch (FileAlreadyExistsException x) {
                      // ignore
                    } catch (IOException x) {
                      String errorMessage = String.format("Unable to create: %s: %s", newdir, x);
                      throw new RuntimeException(errorMessage);
                    }
                    return CONTINUE;
                  }

                  @Override
                  public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                      throws IOException {
                    try {
                      Path dest = target.resolve(source.relativize(file));
                      logger.finest(String.format("[file]=%s [newfile]=%s", file, dest));
                      if (move) {
                        Files.move(file, dest, REPLACE_EXISTING);
                      } else {
                        Files.copy(file, dest, REPLACE_EXISTING);
                      }
                    } catch (IOException x) {
                      String errorMessage =
                          String.format("Unable to %s: %s: %s", move ? "move" : "copy", source, x);
                      throw new IOException(errorMessage);
                    }
                    return CONTINUE;
                  }

                  @Override
                  public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                      throws IOException {
                    logger.finest(String.format("[dir]=%s", dir));
                    if (move) {
                      try {
                        logger.finest(String.format("Deleting %s", dir));
                        Files.delete(dir);
                      } catch (IOException x) {
                        String errorMessage = String.format("Failed to delete directory %s", dir);
                        throw new IOException(errorMessage);
                      }
                    }
                    return CONTINUE;
                  }

                  @Override
                  public FileVisitResult visitFileFailed(Path file, IOException exc)
                      throws IOException {
                    String errorMessage = (exc instanceof FileSystemLoopException)
                        ? String.format("Cycle detected: %s" + file)
                        : String.format("Unable to %s: %s: %s", move ? "move" : "copy", file, exc);
                    throw new IOException(errorMessage);
                  }
                }

                for (Path filePath : filePaths) {
                  Path newFilePath = toDirPath.resolve(filePath.getFileName());
                  TreeCopier treeCopier = new TreeCopier(filePath, newFilePath);
                  logger.info(String.format("%s: \"%s\" -> \"%s\".", move ? "Moving" : "Copying",
                      filePath, newFilePath));
                  Files.walkFileTree(filePath, treeCopier);
                }
              }
            } else if (internalOp instanceof Task.Operation.Delete) {
              Task.Operation.Delete delete = (Task.Operation.Delete) internalOp;
              DELETE: for (FileNameBase fileNameBase : delete.getFileNames()) {
                List<Path> filePaths = new ArrayList<>();
                try {
                  if (fileNameBase instanceof FileName) {
                    filePaths.add(searchFile((FileName) fileNameBase));
                  } else {
                    filePaths = searchFiles((FileNames) fileNameBase);
                  }
                } catch (FileNotFoundException e) {
                  logger.warning(
                      String.format("Trying to delete non-existing file. %s", e.getMessage()));
                  continue DELETE;
                }
                for (Path filePath : filePaths) {
                  logger.info(String.format("Deleting: \"%s\".", filePath));
                  if (Files.isDirectory(filePath)) {
                    Files.walkFileTree(filePath, new SimpleFileVisitor<Path>() {
                      @Override
                      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                          throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                      }

                      @Override
                      public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                          throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                      }
                    });
                  } else {
                    Files.delete(filePath);
                  }
                }
              }
            } else {
              // We should never be here
              String errorMessage =
                  String.format("Program failure. InternalOp has an unknown final type %s.",
                      internalOp.getClass().getName());
              throw new AssertionError(errorMessage);
            }
          }
        } catch (FileNotFoundException | IOException e) {
          throw new RuntimeException(e);
        }
      }
    }

    // For all tasks specified in command line by user
    cliArgs.tasks.stream().map((taskName) -> settings.getTasks().get(taskName)).forEach((task) -> {
      logger.info(String.format("--- Running task \"%s\" ---", task.getName()));
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
    loggingConfig = new LoggingConfig();
    loggingConfig.ensureLogging();
  }

  /**
   * Closes IO.
   */
  private void cleanup() {
    loggingConfig.cleanup();
  }
}
