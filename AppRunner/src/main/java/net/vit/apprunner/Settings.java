package net.vit.apprunner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.vit.apprunner.NameReferenceResolver.Scope;
import net.vit.apprunner.util.Util;

/**
 * A java representation of {@literal <module>.xml}.
 * 
 * @author vit
 */
public class Settings {

  /**
   * Can handle name resolution on its own.
   * @see NameReferenceResolver
   */
  private static interface Resolvable {
    void resolveNames(NameReferenceResolver resolver, Scope scope);
  }

  /**
   * Model for {@literal <file>} tag.
   */
  static class FileName implements Task.Application.ApplicationInput, Resolvable {
    private String in;
    private Optional<String> startsWith = Optional.empty();
    private Optional<String> endsWith = Optional.empty();
    private Optional<String> contains = Optional.empty();

    FileName(String in) {
      this.in = in;
    }

    String getIn() {
      return in;
    }

    Optional<String> getStartsWith() {
      return startsWith;
    }

    Optional<String> getEndsWith() {
      return endsWith;
    }

    Optional<String> getContains() {
      return contains;
    }

    void setStartsWith(String val) {
      this.startsWith = Optional.of(val);
    }

    void setEndsWith(String val) {
      this.endsWith = Optional.of(val);
    }

    void setContains(String val) {
      this.contains = Optional.of(val);
    }

    private void setIn(String val) {
      this.in = val;
    }

    @Override
    public void resolveNames(NameReferenceResolver resolver, Scope scope) {
      setIn(Util.correctFileSeparator(resolver.resolve(in, scope)));
      contains.ifPresent((val) -> setContains(resolver.resolve(val, scope)));
      startsWith.ifPresent((val) -> setStartsWith(resolver.resolve(val, scope)));
      endsWith.ifPresent((val) -> setEndsWith(resolver.resolve(val, scope)));
    }
  }

  /**
   * Model for {@litaral <configuration>} tag.
   */
  static class Configuration {
    /**
     * Model for {@literal <operation-def>} tag.
     */
    static class OperationDef implements Resolvable {
      private final String name;
      private final Task.Operation operation;

      OperationDef(String name, Task.Operation operation) {
        this.name = name;
        this.operation = operation;
      }

      String getName() {
        return name;
      }

      Task.Operation getOperation() {
        return operation;
      }

      @Override
      public void resolveNames(NameReferenceResolver resolver, Scope scope) {
        operation.resolveNames(resolver, scope);
      }
    }

    private final Map<String, String> constants;
    private final Map<String, Configuration.OperationDef> operationDefs;

    Configuration() {
      this.constants = new HashMap<>();
      this.operationDefs = new HashMap<>();
    }

    Map<String, String> getConstants() {
      return Collections.unmodifiableMap(constants);
    }

    String putConstant(String key, String value) {
      return constants.put(key, value);
    }

    Map<String, OperationDef> getOperationDefs() {
      return Collections.unmodifiableMap(operationDefs);
    }

    OperationDef putOperationDef(String key, OperationDef value) {
      return operationDefs.put(key, value);
    }
  }

  /**
   * Model for {@literal <task>} tag.
   */
  static class Task {
    static interface Action {

    }
    
    /**
     * Model for {@literal <application>} tag.
     */
    static class Application implements Action, Resolvable {
      static interface ApplicationInput {

      }

      static class StringArg implements ApplicationInput, Resolvable {
        private String value;

        StringArg(String value) {
          this.value = value;
        }

        String getValue() {
          return value;
        }

        private void setValue(String value) {
          this.value = value;
        }

        @Override
        public void resolveNames(NameReferenceResolver resolver, Scope scope) {
          setValue(resolver.resolve(value, scope));
        }
      }

      private String executablePath;
      private final List<List<ApplicationInput>> executeList;

      Application(String executablePath) {
        this.executablePath = executablePath;
        this.executeList = new ArrayList<>();
      }

      String getExecutablePath() {
        return executablePath;
      }

      void setExecutablePath(String val) {
        this.executablePath = val;
      }

      List<List<ApplicationInput>> getExecuteList() {
        return Collections.unmodifiableList(executeList);
      }

      void addExecute(List<ApplicationInput> execute) {
        executeList.add(execute);
      }

      void addAllExecutes(Collection<? extends List<ApplicationInput>> executeList) {
        this.executeList.addAll(executeList);
      }

      @Override
      public void resolveNames(NameReferenceResolver resolver, Scope scope) {
        setExecutablePath(Util.correctFileSeparator(resolver.resolve(executablePath, scope)));
        executeList.stream().flatMap(List::stream).filter(Resolvable.class::isInstance)
            .map(Resolvable.class::cast)
            .forEach((resolvable) -> resolvable.resolveNames(resolver, scope));
      }
    }

    /**
     * Model for {@literal <operation>} tag.
     */
    static class Operation implements Action, Resolvable {
      static interface InternalOp extends Resolvable {

      }

      /**
       * Model for {@literal <rename>} tag.
       */
      static class Rename implements InternalOp {
        static interface RenameOption {

        }

        /**
         * Model for {@literal <replace-all>} tag.
         */
        static class ReplaceAll implements RenameOption, Resolvable {
          private String substring;
          private String with;

          ReplaceAll(String substring, String with) {
            super();
            this.substring = substring;
            this.with = with;
          }

          String getSubstring() {
            return substring;
          }

          String getWith() {
            return with;
          }

          private void setSubstring(String substring) {
            this.substring = substring;
          }

          private void setWith(String with) {
            this.with = with;
          }

          @Override
          public void resolveNames(NameReferenceResolver resolver, Scope scope) {
            setSubstring(resolver.resolve(substring, scope));
            setWith(resolver.resolve(with, scope));
          }
        }

        private final FileName fileName;
        private final List<RenameOption> renameOptions;

        Rename(FileName fileName, RenameOption... renameOptions) {
          this.fileName = fileName;
          this.renameOptions = new ArrayList<>();
          if (renameOptions != null) {
            Collections.addAll(this.renameOptions, renameOptions);
          }
        }

        FileName getFileName() {
          return fileName;
        }

        List<RenameOption> getRenameOptions() {
          return Collections.unmodifiableList(renameOptions);
        }

        void addRenameOption(RenameOption renameOption) {
          renameOptions.add(renameOption);
        }

        @Override
        public void resolveNames(NameReferenceResolver resolver, Scope scope) {
          fileName.resolveNames(resolver, scope);
          renameOptions.stream().filter(ReplaceAll.class::isInstance).map(ReplaceAll.class::cast)
              .forEach((replaceAll) -> replaceAll.resolveNames(resolver, scope));
        }
      }

      /**
       * Base class for {@link Move} and {@link Copy}.
       */
      static abstract class MoveOrCopy implements InternalOp {
        private final List<FileName> fileNames;
        private String to;

        MoveOrCopy(Collection<? extends FileName> fileNames, String to) {
          this.fileNames = new ArrayList<>(fileNames);
          this.to = to;
        }

        String getTo() {
          return to;
        }

        List<FileName> getFileNames() {
          return Collections.unmodifiableList(fileNames);
        }

        @Override
        public void resolveNames(NameReferenceResolver resolver, Scope scope) {
          fileNames.forEach((fileName) -> fileName.resolveNames(resolver, scope));
          to = Util.correctFileSeparator(resolver.resolve(to, scope));
        }
      }
      
      /**
       * Model for {@literal <move>} tag.
       */
      static class Move extends MoveOrCopy {
        public Move(Collection<? extends FileName> fileNames, String to) {
          super(fileNames, to);
        }
      }
      
      /**
       * Model for {@literal <copy>} tag.
       */
      static class Copy extends MoveOrCopy {
        public Copy(Collection<? extends FileName> fileNames, String to) {
          super(fileNames, to);
        }
      }
      
      /**
       * Model for {@literal <delete>} tag.
       */
      static class Delete implements InternalOp {
        private final List<FileName> fileNames;

        Delete(Collection<? extends FileName> fileNames) {
          this.fileNames = new ArrayList<>(fileNames);
        }

        List<FileName> getFileNames() {
          return Collections.unmodifiableList(fileNames);
        }

        @Override
        public void resolveNames(NameReferenceResolver resolver, Scope scope) {
          fileNames.forEach((fileName) -> fileName.resolveNames(resolver, scope));
        }
      }

      private final List<InternalOp> internals;

      Operation(List<InternalOp> internals) {
        this.internals = internals;
      }

      List<InternalOp> getInternals() {
        return Collections.unmodifiableList(internals);
      }

      void addInternalOp(InternalOp internalOp) {
        internals.add(internalOp);
      }

      @Override
      public void resolveNames(NameReferenceResolver resolver, Scope scope) {
        internals.forEach((internalOp) -> internalOp.resolveNames(resolver, scope));
      }
    }

    /**
     * Model for {@literal <operation>} tag with an attribute {@code ref}.
     */
    static class OperationRef implements Action, Resolvable {
      private String ref;

      OperationRef(String ref) {
        this.ref = ref;
      }

      String getRef() {
        return ref;
      }

      @Override
      public void resolveNames(NameReferenceResolver resolver, Scope scope) {
        ref = resolver.resolve(ref, scope);
      }
    }

    private final String name;
    private final Map<String, String> constants;
    private final List<Action> actions;

    Task(String name, Action... actions) {
      this.name = name;
      this.constants = new HashMap<>();
      this.actions = new ArrayList<Task.Action>();
      if (actions != null) {
        Collections.addAll(this.actions, actions);
      }
    }

    String getName() {
      return name;
    }

    Map<String, String> getConstants() {
      return Collections.unmodifiableMap(constants);
    }

    void putConstant(String name, String value) {
      constants.put(name, value);
    }

    void putAllConstants(Map<? extends String, ? extends String> constants) {
      this.constants.putAll(constants);
    }

    List<Action> getActions() {
      return Collections.unmodifiableList(actions);
    }

    void addAction(Action action) {
      actions.add(action);
    }

    void addAllActions(Collection<? extends Action> actions) {
      this.actions.addAll(actions);
    }
  }

  private Configuration configuration;
  private final Map<String, Task> tasks;

  Settings() {
    this.tasks = new HashMap<>();
  }

  Configuration getConfiguration() {
    return configuration;
  }

  void setConfiguration(Configuration configuration) {
    this.configuration = configuration;
  }

  Map<String, Task> getTasks() {
    return Collections.unmodifiableMap(tasks);
  }

  void putTask(String name, Task task) {
    tasks.put(name, task);
  }

  void putAllTasks(Map<? extends String, ? extends Task> tasks) {
    this.tasks.putAll(tasks);
  }
}
