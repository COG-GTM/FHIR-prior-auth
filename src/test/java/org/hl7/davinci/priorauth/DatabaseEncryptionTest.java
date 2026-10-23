package org.hl7.davinci.priorauth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class DatabaseEncryptionTest {

  private static String dbFile;

  @BeforeClass
  public static void setupClass() {
    App.initializeAppDB();
    String env = System.getenv(Database.DB_FILE_ENV);
    dbFile = "./" + (env == null || env.isBlank() ? "databaseData/database" : env);
  }

  @Test
  public void testDatabaseFileIsAesEncrypted() throws IOException {
    Path path = Paths.get(dbFile + ".mv.db");
    Assert.assertTrue(Files.exists(path));
    byte[] header = new byte[9];
    try (InputStream in = Files.newInputStream(path)) {
      Assert.assertEquals(header.length, in.readNBytes(header, 0, header.length));
    }
    // H2 encrypted files start with "H2encrypt"; plaintext MVStore files start with "H:2"
    Assert.assertEquals("H2encrypt", new String(header, StandardCharsets.US_ASCII));
  }

  @Test
  public void testDatabaseCannotBeOpenedWithWrongPassword() {
    Assert.assertThrows(SQLException.class, () -> DriverManager
        .getConnection("jdbc:h2:" + dbFile + ";CIPHER=AES;IFEXISTS=TRUE", "", "wrongpassword ").close());
  }

  @Test
  public void testFilePasswordIsRequired() {
    Assert.assertThrows(IllegalStateException.class, () -> Database.resolveFilePassword(null));
    Assert.assertThrows(IllegalStateException.class, () -> Database.resolveFilePassword(""));
    Assert.assertThrows(IllegalStateException.class, () -> Database.resolveFilePassword("has space"));
    Assert.assertEquals("s3cret", Database.resolveFilePassword("s3cret"));
  }
}
