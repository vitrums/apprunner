package net.vit.apprunner;

/**
 * Class, containing {@code main} method. Creates an instance of {@link AppRunner} and calls
 * {@link AppRunner#launch()} against it.
 * 
 * @author vit
 */
public class Main {
  public static void main(String[] args) {
    AppRunner appRunner = new AppRunner(args);
    appRunner.launch();
  }
}
