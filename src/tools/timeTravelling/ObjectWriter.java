package tools.timeTravelling;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Base64;

import som.vmobjects.SObject;
import som.vmobjects.SObject.SMutableObject;

//this class allows the state of a root node to be written and read to/from file or database
public  class ObjectWriter {
  private static java.util.Base64.Encoder encoder = Base64.getEncoder();
  private static java.util.Base64.Decoder decoder = Base64.getDecoder();


  public static String serializeObject(final Object object) throws IOException {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    ObjectOutputStream so = new ObjectOutputStream(bo);
    so.writeObject(object);
    so.flush();
    return new String(encoder.encode(bo.toByteArray()));
  }

  public static  Object decodeObject(final String string) throws IOException, ClassNotFoundException {
    byte[] b = decoder.decode(string.getBytes());
    ByteArrayInputStream bi = new ByteArrayInputStream(b);
    ObjectInputStream si = new ObjectInputStream(bi);
    return si.readObject();
  }

  // while reading we want this information: SClass instanceClass, ClassFactory factory, ObjectLayout layout)

  public static void writeTargetArgument(final Object t) {
    if (t instanceof SMutableObject) {
      // TODO ensure the platform is the only possible immutable top level object
      SMutableObject target = (SMutableObject) t;
      System.out.println(target.getObjectLayout().getStorageLocations());


      // object layout: ObjectLayout(final HashSet<SlotDefinition> slots, final ClassFactory forClasses, final boolean isTransferObject)
      // ObjectLayout layout = target.getObjectLayout();
      // layout.getStorageTypes();
    } else {
      System.out.println("ignored: " + t.getClass() + " " + t.toString());
    }
  }

  public static String getClassName(final SObject object) {
    return object.getSOMClass().getName().getString();
  }
}
