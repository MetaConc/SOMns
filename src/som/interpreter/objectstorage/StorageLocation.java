package som.interpreter.objectstorage;

import java.lang.reflect.Field;

import org.neo4j.driver.v1.Session;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.profiles.IntValueProfile;

import som.compiler.MixinDefinition.SlotDefinition;
import som.interpreter.TruffleCompiler;
import som.interpreter.objectstorage.FieldReadNode.ReadObjectFieldNode;
import som.interpreter.objectstorage.FieldReadNode.ReadSetOrUnsetPrimitiveSlot;
import som.interpreter.objectstorage.FieldReadNode.ReadSetPrimitiveSlot;
import som.interpreter.objectstorage.FieldReadNode.ReadUnwrittenFieldNode;
import som.vm.constants.Nil;
import som.vmobjects.SObject;
import sun.misc.Unsafe;
import tools.timeTravelling.Database;


public abstract class StorageLocation {
  // if an object is instantiated it is dirty.
  public boolean dirty = true;
  protected Object databaseRef = null;

  public abstract boolean isDirty(Database db, Session session, SObject object);
  public Object getDatabaseRef() {
    return databaseRef;
  }

  private static Unsafe loadUnsafe() {
    try {
      return Unsafe.getUnsafe();
    } catch (SecurityException e) {
      // can fail, is ok, just to the fallback below
    }
    try {
      Field theUnsafeInstance = Unsafe.class.getDeclaredField("theUnsafe");
      theUnsafeInstance.setAccessible(true);
      return (Unsafe) theUnsafeInstance.get(Unsafe.class);
    } catch (Exception e) {
      throw new RuntimeException("exception while trying to get Unsafe.theUnsafe via reflection:", e);
    }
  }

  private static final Unsafe unsafe = loadUnsafe();

  public static long getFieldOffset(final Field field) {
    return unsafe.objectFieldOffset(field);
  }

  public interface LongStorageLocation {
    boolean isSet(SObject obj, IntValueProfile primMarkProfile);
    void markAsSet(SObject obj);
    long readLongSet(SObject obj);
    void writeLongSet(SObject obj, long value);
  }

  public interface DoubleStorageLocation {
    boolean isSet(SObject obj, IntValueProfile primMarkProfile);
    void markAsSet(SObject obj);
    double readDoubleSet(SObject obj);
    void   writeDoubleSet(SObject obj, double value);
  }

  public static StorageLocation createForLong(final ObjectLayout layout,
      final SlotDefinition slot, final int primFieldIndex) {
    if (primFieldIndex < SObject.NUM_PRIMITIVE_FIELDS) {
      return new LongDirectStoreLocation(layout, slot, primFieldIndex);
    } else {
      return new LongArrayStoreLocation(layout, slot, primFieldIndex);
    }
  }

  public static StorageLocation createForDouble(final ObjectLayout layout,
      final SlotDefinition slot, final int primFieldIndex) {
    if (primFieldIndex < SObject.NUM_PRIMITIVE_FIELDS) {
      return new DoubleDirectStoreLocation(layout, slot, primFieldIndex);
    } else {
      return new DoubleArrayStoreLocation(layout, slot, primFieldIndex);
    }
  }

  public static StorageLocation createForObject(final ObjectLayout layout,
      final SlotDefinition slot, final int objFieldIndex) {
    if (objFieldIndex < SObject.NUM_PRIMITIVE_FIELDS) {
      return new ObjectDirectStorageLocation(layout, slot, objFieldIndex);
    } else {
      return new ObjectArrayStorageLocation(layout, slot, objFieldIndex);
    }
  }

  protected final ObjectLayout layout;
  protected final SlotDefinition slot;

  protected StorageLocation(final ObjectLayout layout, final SlotDefinition slot) {
    this.layout = layout;
    this.slot   = slot;
  }

  public abstract boolean isSet(SObject obj, IntValueProfile primMarkProfile);

  /**
   * @return true, if it is an object location, false otherwise.
   */
  public abstract boolean isObjectLocation();

  public abstract Object read(SObject obj);
  public abstract void   write(SObject obj, Object value);

  public abstract FieldReadNode getReadNode(boolean isSet);

  public static final class UnwrittenStorageLocation extends StorageLocation {

    public UnwrittenStorageLocation(final ObjectLayout layout, final SlotDefinition slot) {
      super(layout, slot);
    }

    @Override
    public boolean isSet(final SObject obj, final IntValueProfile primMarkProfile) {
      return false;
    }

    @Override
    public boolean isObjectLocation() {
      return false;
    }

    @Override
    public Object read(final SObject obj) {
      CompilerAsserts.neverPartOfCompilation("StorageLocation");
      return Nil.nilObject;
    }

    @Override
    public void write(final SObject obj, final Object value) {
      CompilerAsserts.neverPartOfCompilation("StorageLocation");
      dirty = true;
      ObjectTransitionSafepoint.INSTANCE.writeUninitializedSlot(obj, slot, value);
    }

    @Override
    public FieldReadNode getReadNode(final boolean isSet) {
      return new ReadUnwrittenFieldNode(slot);
    }

    @Override
    public boolean isDirty(final Database db, final Session session, final SObject object) {
      // Nils are not stored in the db
      return false;
    }
  }

  public abstract static class AbstractObjectStorageLocation extends StorageLocation {
    public AbstractObjectStorageLocation(final ObjectLayout layout, final SlotDefinition slot) {
      super(layout, slot);
    }

    @Override
    public final boolean isObjectLocation() {
      return true;
    }

    @Override
    public final FieldReadNode getReadNode(final boolean isSet) {
      return new ReadObjectFieldNode(slot, layout);
    }
  }

  public static final class ObjectDirectStorageLocation
  extends AbstractObjectStorageLocation {
    private final long fieldOffset;
    public ObjectDirectStorageLocation(final ObjectLayout layout, final SlotDefinition slot,
        final int objFieldIdx) {
      super(layout, slot);
      fieldOffset = SObject.getObjectFieldOffset(objFieldIdx);
    }

    @Override
    public boolean isSet(final SObject obj, final IntValueProfile primMarkProfile) {
      assert read(obj) != null;
      return true;
    }

    @Override
    public Object read(final SObject obj) {
      return unsafe.getObject(obj, fieldOffset);
    }

    @Override
    public void write(final SObject obj, final Object value) {
      assert value != null;
      dirty = true;
      unsafe.putObject(obj, fieldOffset, value);
    }

    @Override
    public boolean isDirty(final Database database, final Session session, final SObject object) {
      Object value = object.readSlot(slot);
      if (value instanceof Boolean) {
        if(dirty) {
          if (isSet(object, null)) {
            databaseRef = database.storeBoolean(session, (Boolean) value);
            dirty = false;
          }
          return true;
        }
        return false;
      } else if (value instanceof String) {
        if(dirty) {
          if (isSet(object, null)) {
            databaseRef = database.storeString(session, (String) value);
            dirty = false;
          }
          return true;
        }
        return false;
      } else if (value instanceof SObject) {
        SObject slotObject = (SObject) value;
        slotObject.isDirty(database, session);

        /*
         * If both object A and object B refer to same C.
         * A's reference to C is dirty if the ref in slotRef does not match the ref in C after the isDirty call on C.
         */

        Object slotObjectRef = slotObject.getDatabaseRef();
        boolean isDirty = (databaseRef != slotObjectRef);
        databaseRef = slotObjectRef;
        return isDirty;
      }
      System.out.println("is dirty on object storage location: " + object.readSlot(slot).getClass());
      return false;
    }
  }

  public static final class ObjectArrayStorageLocation extends AbstractObjectStorageLocation {
    private final int extensionIndex;
    public ObjectArrayStorageLocation(final ObjectLayout layout,
        final SlotDefinition slot, final int objFieldIdx) {
      super(layout, slot);
      extensionIndex = objFieldIdx - SObject.NUM_OBJECT_FIELDS;
    }

    @Override
    public boolean isSet(final SObject obj, final IntValueProfile primMarkProfile) {
      assert read(obj) != null;
      return true;
    }

    @Override
    public Object read(final SObject obj) {
      Object[] arr = obj.getExtensionObjFields();
      return arr[extensionIndex];
    }

    @Override
    public void write(final SObject obj, final Object value) {
      assert value != null;
      dirty = true;
      Object[] arr = obj.getExtensionObjFields();
      arr[extensionIndex] = value;
    }

    @Override
    public boolean isDirty(final Database db, final Session session, final SObject object) {
      // TODO Auto-generated method stub
      return false;
    }
  }

  public abstract static class PrimitiveStorageLocation extends StorageLocation {
    protected final int mask;

    protected PrimitiveStorageLocation(final ObjectLayout layout,
        final SlotDefinition slot, final int primField) {
      super(layout, slot);
      mask = SObject.getPrimitiveFieldMask(primField);
    }

    @Override
    public final boolean isSet(final SObject obj, final IntValueProfile primMarkProfile) {
      return obj.isPrimitiveSet(mask, primMarkProfile);
    }

    @Override
    public final boolean isObjectLocation() {
      return false;
    }

    public final void markAsSet(final SObject obj) {
      obj.markPrimAsSet(mask);
    }

    public abstract Object readSet(SObject obj);
    public abstract void   writeSet(SObject obj, Object value);
  }

  public abstract static class PrimitiveDirectStoreLocation extends PrimitiveStorageLocation {
    protected final long offset;
    public PrimitiveDirectStoreLocation(final ObjectLayout layout,
        final SlotDefinition slot, final int primField) {
      super(layout, slot, primField);
      offset = SObject.getPrimitiveFieldOffset(primField);
    }

    @Override
    public final FieldReadNode getReadNode(final boolean isSet) {
      if (isSet) {
        return new ReadSetPrimitiveSlot(slot, layout);
      } else {
        return new ReadSetOrUnsetPrimitiveSlot(slot, layout);
      }
    }
  }

  public static final class DoubleDirectStoreLocation extends PrimitiveDirectStoreLocation
  implements DoubleStorageLocation {
    private final IntValueProfile primMarkProfile = IntValueProfile.createIdentityProfile();
    public DoubleDirectStoreLocation(final ObjectLayout layout,
        final SlotDefinition slot, final int primField) {
      super(layout, slot, primField);
    }

    @Override
    public Object read(final SObject obj) {
      if (isSet(obj, primMarkProfile)) {
        return readDoubleSet(obj);
      } else {
        return Nil.nilObject;
      }
    }

    @Override
    public double readDoubleSet(final SObject obj) {
      return unsafe.getDouble(obj, offset);
    }

    @Override
    public Object readSet(final SObject obj) {
      return readDoubleSet(obj);
    }

    @Override
    public void writeSet(final SObject obj, final Object value) {
      writeDoubleSet(obj, (double) value);
    }

    @Override
    public void write(final SObject obj, final Object value) {
      assert value != null;
      dirty = true;
      if (value instanceof Double) {
        writeDoubleSet(obj, (double) value);
        markAsSet(obj);
      } else {
        TruffleCompiler.transferToInterpreterAndInvalidate("unstabelized read node");
        assert value != Nil.nilObject;
        ObjectTransitionSafepoint.INSTANCE.writeAndGeneralizeSlot(obj, slot, value);
      }
    }

    @Override
    public void writeDoubleSet(final SObject obj, final double value) {
      dirty = true;
      unsafe.putDouble(obj, offset, value);
    }

    @Override
    public boolean isDirty(final Database db, final Session session, final SObject object) {
      if(dirty) {
        if (isSet(object, primMarkProfile)) {
          databaseRef = db.storeDouble(session, readDoubleSet(object));
          dirty = false;
        }
        return true;
      }
      return false;
    }
  }

  public static final class LongDirectStoreLocation extends PrimitiveDirectStoreLocation
  implements LongStorageLocation {
    private final IntValueProfile primMarkProfile = IntValueProfile.createIdentityProfile();
    public LongDirectStoreLocation(final ObjectLayout layout,
        final SlotDefinition slot, final int primField) {
      super(layout, slot, primField);
    }

    @Override
    public Object read(final SObject obj) {
      if (isSet(obj, primMarkProfile)) {
        return readLongSet(obj);
      } else {
        return Nil.nilObject;
      }
    }

    @Override
    public long readLongSet(final SObject obj) {
      return unsafe.getLong(obj, offset);
    }

    @Override
    public Object readSet(final SObject obj) {
      return readLongSet(obj);
    }

    @Override
    public void writeSet(final SObject obj, final Object value) {
      writeLongSet(obj, (long) value);
    }

    @Override
    public void write(final SObject obj, final Object value) {
      assert value != null;
      dirty = true;
      if (value instanceof Long) {
        writeLongSet(obj, (long) value);
        markAsSet(obj);
      } else {
        TruffleCompiler.transferToInterpreterAndInvalidate("unstabelized write node");
        ObjectTransitionSafepoint.INSTANCE.writeAndGeneralizeSlot(obj, slot, value);
      }
    }

    @Override
    public void writeLongSet(final SObject obj, final long value) {
      dirty = true;
      unsafe.putLong(obj, offset, value);
    }

    @Override
    public boolean isDirty(final Database db, final Session session, final SObject object) {
      if(dirty) {
        if (isSet(object, primMarkProfile)) {
          databaseRef = db.storeLong(session, readLongSet(object));
          dirty = false;
        }
        return true;
      }
      return false;
    }
  }

  public abstract static class PrimitiveArrayStoreLocation extends PrimitiveStorageLocation {
    protected final int extensionIndex;
    public PrimitiveArrayStoreLocation(final ObjectLayout layout,
        final SlotDefinition slot, final int primField) {
      super(layout, slot, primField);
      extensionIndex = primField - SObject.NUM_PRIMITIVE_FIELDS;
      assert extensionIndex >= 0;
    }

    @Override
    public FieldReadNode getReadNode(final boolean isSet) {
      if (isSet) {
        return new ReadSetPrimitiveSlot(slot, layout);
      } else {
        return new ReadSetOrUnsetPrimitiveSlot(slot, layout);
      }
    }
  }

  public static final class LongArrayStoreLocation extends PrimitiveArrayStoreLocation
  implements LongStorageLocation {
    private final IntValueProfile primMarkProfile = IntValueProfile.createIdentityProfile();
    public LongArrayStoreLocation(final ObjectLayout layout,
        final SlotDefinition slot, final int primField) {
      super(layout, slot, primField);
    }

    @Override
    public Object read(final SObject obj) {
      if (isSet(obj, primMarkProfile)) {
        return readLongSet(obj);
      } else {
        return Nil.nilObject;
      }
    }

    @Override
    public long readLongSet(final SObject obj) {
      return obj.getExtendedPrimFields()[extensionIndex];
    }

    @Override
    public Object readSet(final SObject obj) {
      return readLongSet(obj);
    }

    @Override
    public void writeSet(final SObject obj, final Object value) {
      writeLongSet(obj, (long) value);
    }

    @Override
    public void write(final SObject obj, final Object value) {
      assert value != null;
      dirty = true;
      if (value instanceof Long) {
        writeLongSet(obj, (long) value);
        markAsSet(obj);
      } else {
        TruffleCompiler.transferToInterpreterAndInvalidate("unstabelized write node");
        assert value != Nil.nilObject;
        ObjectTransitionSafepoint.INSTANCE.writeAndGeneralizeSlot(obj, slot, value);
      }
    }

    @Override
    public void writeLongSet(final SObject obj, final long value) {
      dirty = true;
      obj.getExtendedPrimFields()[extensionIndex] = value;
    }

    @Override
    public boolean isDirty(final Database db, final Session session, final SObject object) {
      // TODO Auto-generated method stub
      return false;
    }
  }

  public static final class DoubleArrayStoreLocation extends PrimitiveArrayStoreLocation
  implements DoubleStorageLocation {
    public DoubleArrayStoreLocation(final ObjectLayout layout,
        final SlotDefinition slot, final int primField) {
      super(layout, slot, primField);
    }

    private final IntValueProfile primMarkProfile = IntValueProfile.createIdentityProfile();

    @Override
    public Object read(final SObject obj) {
      if (isSet(obj, primMarkProfile)) {
        return readDoubleSet(obj);
      } else {
        return Nil.nilObject;
      }
    }

    @Override
    public double readDoubleSet(final SObject obj) {
      long[] arr = obj.getExtendedPrimFields();
      return unsafe.getDouble(arr,
          (long) Unsafe.ARRAY_DOUBLE_BASE_OFFSET + Unsafe.ARRAY_DOUBLE_INDEX_SCALE * extensionIndex);
    }

    @Override
    public Object readSet(final SObject obj) {
      return readDoubleSet(obj);
    }

    @Override
    public void writeSet(final SObject obj, final Object value) {
      writeDoubleSet(obj, (double) value);
    }

    @Override
    public void write(final SObject obj, final Object value) {
      assert value != null;
      dirty = true;;
      if (value instanceof Double) {
        writeDoubleSet(obj, (double) value);
        markAsSet(obj);
      } else {
        TruffleCompiler.transferToInterpreterAndInvalidate("unstabelized write node");
        assert value != Nil.nilObject;
        ObjectTransitionSafepoint.INSTANCE.writeAndGeneralizeSlot(obj, slot, value);
      }
    }

    @Override
    public void writeDoubleSet(final SObject obj, final double value) {
      dirty = true;
      final long[] arr = obj.getExtendedPrimFields();
      unsafe.putDouble(arr,
          (long) Unsafe.ARRAY_DOUBLE_BASE_OFFSET + Unsafe.ARRAY_DOUBLE_INDEX_SCALE * this.extensionIndex,
          value);
    }

    @Override
    public boolean isDirty(final Database db, final Session session, final SObject object) {
      // TODO Auto-generated method stub
      return false;
    }
  }
}
