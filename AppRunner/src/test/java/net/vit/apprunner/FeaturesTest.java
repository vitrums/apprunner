package net.vit.apprunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.Before;
import org.junit.Test;

public class FeaturesTest {
  private static final Path destPath = Paths.get("dir_for_integration_testing/destination");
  
  private String[] getCliForTasks(String... tasks) {
    String args = "-m test\\module1.xml -p test\\myconstants.properties -t";
    for (String task : tasks) {
      args += " " + task;
    }

    return args.split(" ");
  }

  private static boolean filesExists(String... fileNames) {
    assert(fileNames.length > 0);
    boolean result = true;
    for (String fileName : fileNames) {
      result &= Files.exists(destPath.resolve(fileName));
    }
    return result;
  }
  
  private static boolean filesExists(Path... paths) {
    assert(paths.length > 0);
    boolean result = true;
    for (Path path : paths) {
      result &= Files.exists(destPath.resolve(path));
    }
    return result;
  }

  @Before
  public void cleanup() {
    if (Files.isDirectory(destPath)) {
      try {
        Files.walkFileTree(destPath, new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Test
  public void testApplicationRunning() {
    cleanup();
    AppRunner appRunner = new AppRunner(getCliForTasks("run_uasset_on_two_files"));
    appRunner.launch();
    assertTrue(filesExists("CI_lil_bdl_jeans.uasset"));
  }
  
  @Test
  public void testMovingFolders() {
    cleanup();
    AppRunner appRunner = new AppRunner(getCliForTasks("copy_dir001_to_x_and_y_then_abc_to_dir001_in_x_and_then_dir001_from_y_to_x"));
    appRunner.launch();
    assertTrue(filesExists("x/dir001", "y/dir001", "x/abc.txt", "x/dir001/dir001_01/lalala.txt"));
  }

  @Test
  public void testCopyingRenamingAndDeliting() {
    cleanup();
    AppRunner appRunner = new AppRunner(getCliForTasks("copy_123"));
    appRunner.launch();
    assertTrue(filesExists("new/a123.txt"));
    assertTrue(filesExists("new/b123.txt"));
    appRunner = new AppRunner(getCliForTasks("rename_123_in_new"));
    appRunner.launch();
    assertTrue(filesExists("new/a321.txt"));
    assertTrue(filesExists("new/b321.txt"));
    appRunner = new AppRunner(getCliForTasks("delete_321_in_new"));
    appRunner.launch();
    assertFalse(filesExists("new/a321.txt"));
    assertFalse(filesExists("new/b321.txt"));
  }

  @Test
  public void testModuleInheritance() {
    cleanup();
    String args = "-m test\\module2.xml -p test\\myconstants.properties -t copy_123";
    AppRunner appRunner = new AppRunner(args.split(" "));
    appRunner.launch();
    assertTrue(filesExists("new/a123.txt"));
    assertTrue(filesExists("new/b123.txt"));

    args = "-m test\\module2.xml -p test\\myconstants.properties -t delete_123_in_new";
    appRunner = new AppRunner(args.split(" "));
    appRunner.launch();
    assertFalse(filesExists("new/a123.txt"));
    assertFalse(filesExists("new/b123.txt"));
  }
}
