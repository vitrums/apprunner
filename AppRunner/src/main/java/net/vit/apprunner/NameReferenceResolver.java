package net.vit.apprunner;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encapsulates the functionality of "dereferencing" names. I.e. for the string "John ${surname}",
 * if {@code surname=Doe}, then the resolved string is {@code "John Doe"}.
 * 
 * @author vit
 */
public class NameReferenceResolver {
  private static final Logger logger = AppRunner.logger;
  // s = ${bar}aaa${baz}bbb${zox}
  // matcher.groupCount() = 4
  // while (matcher.find())
  // Iter 1: 0: ${bar}aaa 1: 2: ${bar} 3: bar 4: aaa
  // Iter 2: 0: ${baz}bbb 1: 2: ${baz} 3: baz 4: bbb
  // Iter 3: 0: ${zox} 1: 2: ${zox} 3: zox 4:
  private static final String PATTERN_PART =
      "([^\\$\\{\\}]*)(\\$\\{([^\\$\\{\\}]+)\\}){1}([^\\$\\{\\}]*)";
  private static final String PATTERN_ALL = "(" + PATTERN_PART + ")+";

  /**
   * Defines a hierarchy of name scopes.
   * <p/>
   * Two levels: highest one - {@link #GLOBAL}, which corresponds to {@literal <constants>} section
   * defined under {@literal <configuration>} and the second one, which contains scopes
   * corresponding to {@literal <constants>} section defined under specific tasks.
   */
  static class Scope {
    /**
     * Corresponds to constants defined in {@literal <configuration> section}.
     */
    static final Scope GLOBAL = Scope.of("", null);

    static Scope of(String value, Scope parent) {
      return new Scope(value, parent);
    }

    private volatile int hashCode;
    private final String value;
    private final Scope parent;

    Scope(String value, Scope parent) {
      this.value = value;
      this.parent = parent;
    }

    Scope getParent() {
      return parent;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof Scope) {
        Scope scope = (Scope) obj;
        if (!value.equals(scope.value))
          return false;

        if (parent == null)
          if (scope.parent == null)
            return true;
          else
            return false;

        return value.equals(scope.value) && parent.equals(scope.parent);
      }

      return false;
    }

    @Override
    public int hashCode() {
      int result = hashCode;
      if (result == 0) {
        result = 17;
        result = 31 * result + value.hashCode();
        if (parent != null)
          result = 31 * result + parent.hashCode();
        hashCode = result;
      }
      return result;
    }
  }

  private static class SearchResult {
    final String value;
    final Scope scope;

    private SearchResult(String value, Scope scope) {
      this.value = value;
      this.scope = scope;
    }
  }

  private class Dictionary {
    final Map<Scope, Map<String, String>> constants;

    Dictionary() {
      constants = new HashMap<>();
    }

    Optional<SearchResult> search(String name, Scope scope) {
      SearchResult result = null;
      do {
        String value = constants.get(scope).get(name);
        if (value != null) {
          result = new SearchResult(value, scope);
          break;
        }

        scope = scope.getParent();
      } while (scope != null);

      return Optional.ofNullable(result);
    }
  }

  private final Settings settings;
  private final Dictionary resolved;
  private final Dictionary unresolved;
  private final Pattern patternPart;
  private final Pattern patternAll;

  NameReferenceResolver(Settings settings) {
    this.settings = settings;
    resolved = new Dictionary();
    // Put empty dictionaries for each scope (task)
    resolved.constants.put(Scope.GLOBAL, new HashMap<>());
    settings.getTasks().keySet().forEach(
        (taskName) -> resolved.constants.put(Scope.of(taskName, Scope.GLOBAL), new HashMap<>()));
    // Put dictionaries from just parsed XML as is
    unresolved = new Dictionary();
    unresolved.constants.put(Scope.GLOBAL, settings.getConfiguration().getConstants());
    settings.getTasks().keySet().forEach((taskName) -> unresolved.constants
        .put(Scope.of(taskName, Scope.GLOBAL), settings.getTasks().get(taskName).getConstants()));

    patternPart = Pattern.compile(PATTERN_PART);
    patternAll = Pattern.compile(PATTERN_ALL);
  }


  /**
   * For each value option inside {@code valueToResolve} (from first to the last) tries to
   * "dereference" all constants within and produce a simple string value.
   * <p/>
   * E.g. for option "Bill looks for ${a}" the entrance ${a} will be substituted with the real value
   * of constant {@code a}.
   * 
   * @param valueToResolve "${foo}|${bar}aaa${baz}bbb${zox}" like string
   * @param scope a scope that corresponds to a specific task. It denotes, that we resolve names
   *        defined in constants section of this task + constants defined in
   *        {@literal <configuration>}, or only the latter, if the provided scope is
   *        {@link Scope#GLOBAL}
   * @return First of the options (delimiter is the pipe "|" symbol), where we successfully resolved
   *         all constants with the constant names substituted with corresponding values
   * @throws IllegalArgumentException if every option had unresolved names
   */
  String resolve(String valueToResolve, Scope scope) {
    // Don't do anything if arg was null
    if (valueToResolve == null) {
      return null;
    }

    String[] s_arr = valueToResolve.split("\\|");
    logger.finer(String.format("-> Resolving: %s.", Arrays.asList(s_arr)));
    Set<String> unresolvedConstants = new HashSet<>();
    String result = null;
    OPTION: for (int s_i = 0; s_i < s_arr.length; ++s_i) {
      String s = s_arr[s_i].trim();

      if (patternAll.matcher(s).matches()) {
        Matcher matcher = patternPart.matcher(s); // matcher.groupCount() = 4
        // matcher.reset();
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
          String constantName = matcher.group(3);
          String constantValue = resolveConstant(constantName, scope);
          if (constantValue == null) {
            unresolvedConstants.add(constantName);
            continue OPTION;
          }

          sb.append(matcher.group(1)).append(constantValue).append(matcher.group(4));
        }

        // Could resolve all constants in this option
        result = sb.toString();
      } else {
        // Nothing to resolve
        result = s_arr[s_i];
      }

      if (result.equals(s_arr[s_i])) {
        logger.finer("Nothing to resolve.");
      } else {
        logger.finer(String.format("<- Option %d: \"%s\" resolved as: \"%s\".", s_i, s, result));
      }
      return result;
    }

    // unresolvedConstants should never be empty at this point
    if (!unresolvedConstants.isEmpty()) {
      // Coudn't resolve some constants in all options
      String errorMessage = String.format("Unresolved constants %s."
          + " You have to explicitly give the value in <your-module>.xml file"
          + " or in <your-settings>.properties file.", unresolvedConstants);;
      logger.severe(errorMessage);
      throw new IllegalArgumentException(errorMessage);
    } else {
      // We shouldn't ever be here
      String errorMessage = String.format(
          "Program failure. Tried to resolve \"%s\" but didn't return any result.", valueToResolve);
      logger.severe(errorMessage);
      throw new AssertionError(errorMessage);
    }
  }

  /**
   * Returns the value of {@code constant}.
   * 
   * @param constant
   * @param scope
   * @return string value
   * @see {@link #resolveConstantRec(String, Scope, Map)}
   */
  private String resolveConstant(String constant, Scope scope) {
    Map<Scope, Set<String>> stack = new HashMap<>();
    stack.put(Scope.GLOBAL, new HashSet<>());
    settings.getTasks().keySet()
        .forEach((taskName) -> stack.put(Scope.of(taskName, Scope.GLOBAL), new HashSet<>()));
    return resolveConstantRec(constant, scope, stack);
  }

  /**
   * Tries to resolve the value of a constant.
   * <p/>
   * Constant is represented as a combination of options separated with pipe "|" character and might
   * contain a reference to another constant within its value definition, such as in
   * {@literal <}constant name="a" value="Bill ${b}|Bob, ${c} and George Jr. {d}|Mary"
   * /{@literal >}, where {@code a, b} and {@code c} are constants. When such an occurrence of
   * another constant inside the definition of the current {@code constantToResolve} constant is
   * met, this method will recursively call itself in order to "dereference" the new constant.
   * 
   * @param constantToResolve constant, whose value has to be resolved to a simple string containing
   *        no references to any constants
   * @param maxScope scope (level) of this constant. Whenever the method meets a new constant inside
   *        the definition of {@code constantToResolve}, whose values also must be resolved, it will
   *        search only in scope {@code maxScope} or higher up to {@link Scope#GLOBAL}
   * @param stack for each scope it contains a set of constants, which issued a recursive search.
   *        This way we can easily detect and throw and exception as a reaction to cyclic dependency
   *        situation, when {@code a} is defined through {@code b}, and {@code b} is defined through
   *        other constants, which eventually refer to {@code a}
   * @return first value option of this constant for which all references to other constants were
   *         successfully resolved, or {@code null}, if all value options contained at least one
   *         reference to those constants, whose values could not be resolved
   */
  private String resolveConstantRec(String constantToResolve, Scope maxScope,
      Map<Scope, Set<String>> stack) {
    // Search for cyclic dependency
    Scope scopeTmp = maxScope;
    do {
      if (stack.get(scopeTmp).contains(constantToResolve)) {
        // Cyclic dependency
        return null;
      }

      scopeTmp = scopeTmp.getParent();
    } while (scopeTmp != null);
    // Could it be already resolved?
    Optional<SearchResult> maybeValue = resolved.search(constantToResolve, maxScope);
    if (maybeValue.isPresent()) {
      return maybeValue.get().value;
    }
    // Has it been defined at all?
    maybeValue = unresolved.search(constantToResolve, maxScope);
    if (!maybeValue.isPresent()) {
      // Constant hasn't been defined in settings at all
      return null;
    }
    // Try to resolve
    String valueOfConstantToResolve = maybeValue.get().value;
    Scope scopeOfConstantToResolve = maybeValue.get().scope;
    stack.get(scopeOfConstantToResolve).add(constantToResolve);
    String[] s_arr = valueOfConstantToResolve.split("\\|");
    logger.finer(String.format("-> Resolving: %s.", Arrays.asList(s_arr)));
    String result = null;
    OPTION: for (int s_i = 0; s_i < s_arr.length; ++s_i) {
      String s = s_arr[s_i].trim();
      if (patternAll.matcher(s).matches()) {
        Matcher matcher = patternPart.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
          String unknownConstant = matcher.group(3);
          String valueOfUnknownConstant =
              resolveConstantRec(unknownConstant, scopeOfConstantToResolve, stack);
          if (valueOfUnknownConstant == null) {
            continue OPTION;
          }

          sb.append(matcher.group(1)).append(valueOfUnknownConstant).append(matcher.group(4));
        }

        // Could resolve all constants in this option
        result = sb.toString();
      } else {
        // Nothing to resolve
        result = s_arr[s_i];
      }

      resolved.constants.get(scopeOfConstantToResolve).put(constantToResolve, result);
      stack.get(scopeOfConstantToResolve).remove(constantToResolve);
      if (result.equals(s_arr[s_i])) {
        logger.finer("Nothing to resolve.");
      } else {
        logger.finer(String.format("<- Option %d: \"%s\" resolved as: \"%s\".", s_i, s, result));
      }
      return result;
    }

    return null;
  }
}
