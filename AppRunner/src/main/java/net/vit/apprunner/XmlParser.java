package net.vit.apprunner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jdom2.*;
import org.jdom2.input.SAXBuilder;
import org.jdom2.input.sax.XMLReaderJDOMFactory;
import org.jdom2.input.sax.XMLReaderXSDFactory;
import com.github.fge.lambdas.Throwing;
import net.vit.apprunner.Settings.*;
import net.vit.apprunner.util.Util;

/**
 * Encapsulates the functionality of reading and parsing apprunner's {@literal <module>.xml} files.
 * 
 * @author vit
 * @see #parseModuleXml()
 */
public class XmlParser {
  private static final Logger logger = AppRunner.logger;

  private final CliArgs cliArgs;
  private Settings settings;
  private SAXBuilder builder;
  private Set<String> visitedModules;

  public XmlParser(CliArgs cliArgs) {
    this.cliArgs = cliArgs;
    visitedModules = new HashSet<>();
  }

  /**
   * Parses XML document located in file system under the path {@link CliArgs#module}.
   * 
   * @return new {@link Settings} object, representing the {@literal <module>.xml}
   * @throws JDOMException
   * @throws IOException
   */
  Settings parseModuleXml() throws JDOMException, IOException {
    settings = new Settings();

    XMLReaderJDOMFactory factory =
        new XMLReaderXSDFactory(new File(Util.CONFIG_DIR + "apprunner-module.xsd"));
    builder = new SAXBuilder(factory);
    parseModuleRec(cliArgs.module);

    return settings;
  }

  /**
   * Looks for {@literal <inherits>} tags to determine parent modules and parses them in DFS-order.
   * 
   * @param module a valid apprunner's module XML file to parse
   * @throws JDOMException
   * @throws IOException
   */
  private void parseModuleRec(String module) throws JDOMException, IOException {
    if (!visitedModules.add(module)) {
      String errorMessage =
          String.format("Circular module dependency detected. Module \"%s\".", module);
      logger.severe(errorMessage);
      throw new JDOMException(errorMessage);
    }

    Document document = builder.build(new File(Util.CONFIG_DIR + module));
    logger.finer(String.format("Parsing \"%s\".", module));

    Element rootElement = document.getRootElement();
    // <inherits>
    Optional.ofNullable(rootElement.getChild("inherits"))
        .ifPresent((inheritsElement) -> inheritsElement.getChildren("module").stream()
            .map((e) -> e.getAttributeValue("name"))
            .forEach(Throwing.consumer(this::parseModuleRec)));
    // <configuration>
    Optional.ofNullable(rootElement.getChild("configuration")).ifPresent((configurationElement) -> {
      Configuration configuration = new Configuration();
      Optional.ofNullable(configurationElement.getChild("constants"))
          .ifPresent((constantsElement) -> constantsElement.getChildren("constant")
              .forEach((constantElement) -> configuration.putConstant(
                  constantElement.getAttributeValue("name"),
                  constantElement.getAttributeValue("value"))));

      Optional.ofNullable(configurationElement.getChild("actions"))
          .ifPresent((actionElement) -> actionElement.getChildren("operation")
              .forEach(Throwing.consumer((operationElement) -> {
                String name = operationElement.getAttributeValue("name");
                Task.Operation op = createOperation(operationElement);
                Configuration.OperationDef opDef = new Configuration.OperationDef(name, op);
                if (configuration.putOperationDef(name, opDef) != null) {
                  String errorMessage =
                      String.format("More than one operation has the same name \"%s\".", name);
                  logger.severe(errorMessage);
                  throw new JDOMException(errorMessage);
                }
              })));
      settings.setConfiguration(configuration);
    });
    // <tasks>
    Optional.ofNullable(rootElement.getChild("tasks")).ifPresent((tasksElement) -> {
      tasksElement.getChildren("task").forEach((taskElement) -> {
        String name = taskElement.getAttributeValue("name");
        Map<String, String> constants = new HashMap<>();
        // <constants>
        Optional.ofNullable(taskElement.getChild("constants"))
            .ifPresent((constantsElement) -> constantsElement.getChildren("constant").forEach(
                (constantElement) -> constants.put(constantElement.getAttributeValue("name"),
                    constantElement.getAttributeValue("value"))));
        // <actions>
        List<Task.Action> actions = createActions(taskElement.getChild("actions"));
        Task task = new Task(name);
        task.putAllConstants(constants);
        task.addAllActions(actions);
        settings.putTask(name, task);
      });
    });
  }

  /**
   * Parses {@literal <operation>} tag.
   * 
   * @param operationElement
   * @return {@link Task.Operation} object
   */
  private Task.Operation createOperation(Element operationElement) {
    List<Task.Operation.InternalOp> internals = new ArrayList<>();
    for (Element internalOpElement : operationElement.getChildren()) {
      String elementName = internalOpElement.getName();
      if ("rename".equals(elementName)) {
        Element renameElement = internalOpElement;
        FileName fileName = createFileName(renameElement.getChild("file"));
        Task.Operation.Rename rename = new Task.Operation.Rename(fileName);
        renameElement.getChildren().stream()
            .filter((element) -> element.getName().equals("replace-all"))
            .forEach((replaceAllElement) -> {
              String substring = replaceAllElement.getAttributeValue("substring");
              String with = replaceAllElement.getAttributeValue("with");
              Task.Operation.Rename.RenameOption replaceAll =
                  new Task.Operation.Rename.ReplaceAll(substring, with);
              rename.addRenameOption(replaceAll);
            });
        internals.add(rename);
      } else if ("move".equals(elementName) || "copy".equals(elementName)) {
        String to = internalOpElement.getAttributeValue("to");
        List<FileName> fileNames = internalOpElement.getChildren().stream()
            .map((fileElement) -> createFileName(fileElement)).collect(Collectors.toList());
        Task.Operation.MoveOrCopy moveOrCopy =
            "move".equals(elementName) ? new Task.Operation.Move(fileNames, to)
                : new Task.Operation.Copy(fileNames, to);
        internals.add(moveOrCopy);
      } else if ("delete".equals(elementName)) {
        List<FileName> fileNames = internalOpElement.getChildren().stream()
            .map((fileElement) -> createFileName(fileElement)).collect(Collectors.toList());
        Task.Operation.Delete delete = new Task.Operation.Delete(fileNames);
        internals.add(delete);
      } else {
        // We should never be here
        String errorMessage =
            String.format("Program failure. Internal operation is unknown %s.", elementName);
        logger.severe(errorMessage);
        throw new AssertionError(errorMessage);
      }
    }

    return new Task.Operation(internals);
  }

  /**
   * Parses {@literal <file>} tag.
   * 
   * @param fileElement
   * @return {@link FileName} object
   */
  private FileName createFileName(Element fileElement) {
    FileName fileName = new FileName(fileElement.getAttributeValue("in"));
    Optional.ofNullable(fileElement.getAttributeValue("contains")).ifPresent(fileName::setContains);
    Optional.ofNullable(fileElement.getAttributeValue("starts-with"))
        .ifPresent(fileName::setStartsWith);
    Optional.ofNullable(fileElement.getAttributeValue("ends-with"))
        .ifPresent(fileName::setEndsWith);
    if (!fileName.getStartsWith().isPresent() && !fileName.getContains().isPresent()
        && !fileName.getEndsWith().isPresent()) {
      String errorMessage =
          "For element <file> at least one of the arguments \"startsWith\", \"endsWith\" or "
              + "\"contains\" must be specified. Argument in=" + fileName.getIn();
      logger.severe(errorMessage);
      throw new IllegalArgumentException(errorMessage);
    }

    return fileName;
  }

  /**
   * Parses {@literal <application>} tag.
   * 
   * @param applicationElement
   * @return {@link Task.Application} object
   */
  private Task.Application createApplication(Element applicationElement) {
    String executablePath = applicationElement.getAttributeValue("executable");
    List<List<Task.Application.ApplicationInput>> executeList = new ArrayList<>();
    for (Element executeElement : applicationElement.getChildren()) {
      List<Task.Application.ApplicationInput> inputs = new ArrayList<>();
      for (Element cliInputElement : executeElement.getChildren()) {
        switch (cliInputElement.getName()) {
          case "cli-key":
            inputs.add(new Task.Application.StringArg(cliInputElement.getAttributeValue("value")));
            break;
          case "cli-value":
            inputs.add(new Task.Application.StringArg(
                "\"" + cliInputElement.getAttributeValue("value") + "\""));
            break;
          case "file":
            inputs.add(createFileName(cliInputElement));
            break;
          default:
            String errorMessage = "Default case was met. We should never be here.";
            logger.severe(errorMessage);
            throw new AssertionError(errorMessage);
        }
      }
      executeList.add(inputs);
    }

    Task.Application app = new Task.Application(executablePath);
    app.addAllExecutes(executeList);
    return app;
  }

  /**
   * For the given element representing {@literal <actions>} tag, parses all actions within it.
   * 
   * @param actionsElement
   * @return collection of {@link Task.Action} objects, corresponding to actions within this element
   */
  private List<Task.Action> createActions(Element actionsElement) {
    List<Task.Action> actions = new ArrayList<>();
    for (Element actionElement : actionsElement.getChildren()) {
      switch (actionElement.getName()) {
        case "application":
          actions.add(createApplication(actionElement));
          break;
        case "operation":
          String ref = actionElement.getAttributeValue("ref");
          if (ref != null) {
            actions.add(new Task.OperationRef(ref));
          } else {
            actions.add(createOperation(actionElement));
          }

          break;
        default:
          String errorMessage = "Default case was met. We should never be here.";
          logger.severe(errorMessage);
          throw new AssertionError(errorMessage);
      }
    }

    return actions;
  }
}
