/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.avro.specific;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.junit.Test;

/**
 * The {@code java-class} / {@code java-key-class} schema properties must not
 * let an untrusted schema make a reader load and instantiate a class from
 * {@link SpecificDatumReader#DENY_LIST}.
 */
public class TestSpecificDatumReaderDenyList {

  private static final String BAD_ATTRIBUTE = "javax.management.BadAttributeValueExpException";

  private static SpecificDatumReader<Object> defaultReader(Schema schema) {
    SpecificDatumReader<Object> reader = new SpecificDatumReader<>();
    reader.setSchema(schema);
    return reader;
  }

  private static SpecificDatumReader<Object> dataReader(Schema schema, SpecificData data) {
    SpecificDatumReader<Object> reader = new SpecificDatumReader<>(data);
    reader.setSchema(schema);
    return reader;
  }

  /** A {@link SpecificData} that uses the default (non fast) reader. */
  private static SpecificData specificData(ClassLoader loader) {
    SpecificData data = new SpecificData(loader);
    data.setFastReaderEnabled(false);
    return data;
  }

  /** A {@link SpecificData} that uses the {@code FastReaderBuilder}. */
  private static SpecificData fastSpecificData(ClassLoader loader) {
    SpecificData data = new SpecificData(loader);
    data.setFastReaderEnabled(true);
    return data;
  }

  private static ClassLoader appLoader() {
    return TestSpecificDatumReaderDenyList.class.getClassLoader();
  }

  private static Schema stringSchema(String javaClass) {
    return new Schema.Parser().parse("{\"type\":\"string\",\"java-class\":\"" + javaClass + "\"}");
  }

  private static byte[] write(Schema schema, Object datum) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
    new GenericDatumWriter<Object>(schema).write(datum, encoder);
    encoder.flush();
    return out.toByteArray();
  }

  private static Object read(DatumReader<Object> reader, byte[] bytes) throws IOException {
    return reader.read(null, DecoderFactory.get().binaryDecoder(bytes, null));
  }

  private static void assertForbidden(DatumReader<Object> reader, byte[] bytes, String className) {
    SecurityException e = assertThrows(SecurityException.class, () -> read(reader, bytes));
    assertTrue(e.getMessage(), e.getMessage().contains(className));
  }

  /** Every way of building a SpecificDatumReader must reject the class. */
  private static void assertForbiddenEverywhere(Schema schema, byte[] bytes, String className) {
    assertForbidden(defaultReader(schema), bytes, className);
    assertForbidden(dataReader(schema, SpecificData.get()), bytes, className);
    assertForbidden(dataReader(schema, specificData(appLoader())), bytes, className);
    assertForbidden(dataReader(schema, fastSpecificData(appLoader())), bytes, className);
    assertForbidden(new SpecificDatumReader<>(schema), bytes, className);
    assertForbidden(new SpecificDatumReader<>(schema, schema), bytes, className);
    assertForbidden(new SpecificDatumReader<>(schema, schema, specificData(appLoader())), bytes, className);
    assertForbidden(new SpecificDatumReader<>(schema, schema, fastSpecificData(appLoader())), bytes, className);
  }

  private static void assertForbiddenString(String className) throws IOException {
    Schema schema = stringSchema(className);
    assertForbiddenEverywhere(schema, write(schema, "payload"), className);
  }

  @Test
  public void denyListedClassIsRejected() throws IOException {
    assertForbiddenString(BAD_ATTRIBUTE);
  }

  @Test
  public void denyListedJdkClassesAreRejected() throws IOException {
    // "java.net.URLClassLoader" used to be listed by its simple name only and
    // never matched.
    assertForbiddenString("java.net.URLClassLoader");
    assertForbiddenString("javax.script.ScriptEngineManager");
    assertForbiddenString("java.rmi.server.UnicastRemoteObject");
  }

  @Test
  public void denyListedMapKeyClassIsRejected() throws IOException {
    Schema schema = new Schema.Parser()
        .parse("{\"type\":\"map\",\"values\":\"int\",\"java-key-class\":\"" + BAD_ATTRIBUTE + "\"}");
    assertForbiddenEverywhere(schema, write(schema, Collections.singletonMap("key", 1)), BAD_ATTRIBUTE);
  }

  @Test
  public void fastGenericReaderRejectsDenyListedClass() throws IOException {
    Schema schema = stringSchema(BAD_ATTRIBUTE);
    GenericData data = new GenericData();
    data.setFastReaderEnabled(true);
    assertForbidden(new GenericDatumReader<>(schema, schema, data), write(schema, "payload"), BAD_ATTRIBUTE);
  }

  /**
   * "org.mozilla.javascript" is a package entry of the deny list. Rhino is not
   * on the test classpath and the check runs after the class has been loaded, so
   * stand-in classes with those names are defined by a dedicated class loader.
   */
  @Test
  public void classUnderDenyListedPackageIsRejected() throws IOException {
    assertTrue(Arrays.asList(SpecificDatumReader.DENY_LIST).contains("org.mozilla.javascript"));
    for (String className : new String[] { "org.mozilla.javascript.NativeJavaObject",
        "org.mozilla.javascript.tools.shell.Main" }) {
      Schema schema = stringSchema(className);
      byte[] bytes = write(schema, "payload");
      StubClassLoader loader = new StubClassLoader(className);

      // The class resolves through the class loader of the SpecificData.
      assertForbidden(dataReader(schema, specificData(loader)), bytes, className);
      assertForbidden(dataReader(schema, fastSpecificData(loader)), bytes, className);
      assertForbidden(new SpecificDatumReader<>(schema, schema, specificData(loader)), bytes, className);
      assertForbidden(new SpecificDatumReader<>(schema, schema, fastSpecificData(loader)), bytes, className);

      // The class resolves through the thread context class loader.
      Thread thread = Thread.currentThread();
      ClassLoader previous = thread.getContextClassLoader();
      thread.setContextClassLoader(loader);
      try {
        assertForbidden(dataReader(schema, specificData(appLoader())), bytes, className);
      } finally {
        thread.setContextClassLoader(previous);
      }
    }
  }

  @Test
  public void packageEntryOnlyMatchesWholeNameSegments() {
    // "org.mozilla.javascriptx" is not under the "org.mozilla.javascript" package.
    String className = "org.mozilla.javascriptx.Probe";
    StubClassLoader loader = new StubClassLoader(className);
    SpecificDatumReader<Object> reader = new SpecificDatumReader<>(specificData(loader));
    Class<?> stringClass = reader.findStringClass(stringSchema(className));
    assertEquals(className, stringClass.getName());
    assertSame(loader, stringClass.getClassLoader());
  }

  @Test
  public void trustedJavaClassStillDeserializes() throws IOException {
    BigDecimal expected = new BigDecimal("12.34");

    Schema schema = stringSchema(BigDecimal.class.getName());
    byte[] bytes = write(schema, "12.34");
    assertEquals(expected, read(dataReader(schema, specificData(appLoader())), bytes));
    assertEquals(expected, read(new SpecificDatumReader<>(schema, schema, specificData(appLoader())), bytes));

    Schema javaStringSchema = new Schema.Parser().parse(
        "{\"type\":\"string\",\"avro.java.string\":\"String\",\"java-class\":\"" + BigDecimal.class.getName() + "\"}");
    bytes = write(javaStringSchema, "12.34");
    assertEquals(expected, read(defaultReader(javaStringSchema), bytes));
    assertEquals(expected, read(dataReader(javaStringSchema, SpecificData.get()), bytes));
    assertEquals(expected, read(dataReader(javaStringSchema, specificData(appLoader())), bytes));
    assertEquals(expected, read(dataReader(javaStringSchema, fastSpecificData(appLoader())), bytes));
  }

  /** Additions to the untrusted list are honoured by the default reader. */
  @Test
  public void untrustedPackagesArePerReader() throws IOException {
    Schema schema = stringSchema(BigDecimal.class.getName());
    byte[] bytes = write(schema, "12.34");

    SpecificDatumReader<Object> restricted = new SpecificDatumReader<>(specificData(appLoader()));
    assertTrue(restricted.getUntrustedPackages().containsAll(Arrays.asList(SpecificDatumReader.DENY_LIST)));
    restricted.getUntrustedPackages().add("java.math");
    restricted.setSchema(schema);
    assertForbidden(restricted, bytes, BigDecimal.class.getName());

    SpecificDatumReader<Object> fresh = dataReader(schema, specificData(appLoader()));
    assertFalse(fresh.getUntrustedPackages().contains("java.math"));
    assertEquals(new BigDecimal("12.34"), read(fresh, bytes));
  }

  /** Defines an empty public class with the given name. */
  private static final class StubClassLoader extends ClassLoader {
    private final String stubName;

    StubClassLoader(String stubName) {
      super(TestSpecificDatumReaderDenyList.class.getClassLoader());
      this.stubName = stubName;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      if (!stubName.equals(name)) {
        throw new ClassNotFoundException(name);
      }
      byte[] bytes;
      try {
        bytes = emptyClass(name.replace('.', '/'));
      } catch (IOException e) {
        throw new ClassNotFoundException(name, e);
      }
      return defineClass(name, bytes, 0, bytes.length);
    }

    private static byte[] emptyClass(String internalName) throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(0xCAFEBABE); // magic
      out.writeShort(0); // minor version
      out.writeShort(50); // major version (Java 6)
      out.writeShort(5); // constant pool count
      out.writeByte(7); // #1 CONSTANT_Class -> #2
      out.writeShort(2);
      out.writeByte(1); // #2 CONSTANT_Utf8
      out.writeUTF(internalName);
      out.writeByte(7); // #3 CONSTANT_Class -> #4
      out.writeShort(4);
      out.writeByte(1); // #4 CONSTANT_Utf8
      out.writeUTF("java/lang/Object");
      out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
      out.writeShort(1); // this_class
      out.writeShort(3); // super_class
      out.writeShort(0); // interfaces
      out.writeShort(0); // fields
      out.writeShort(0); // methods
      out.writeShort(0); // attributes
      out.flush();
      return bytes.toByteArray();
    }
  }
}
