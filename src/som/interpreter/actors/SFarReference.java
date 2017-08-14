package som.interpreter.actors;

import org.neo4j.driver.v1.Session;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

import som.vmobjects.SAbstractObject;
import som.vmobjects.SClass;
import tools.timeTravelling.Database;


public final class SFarReference extends SAbstractObject {
  @CompilationFinal private static SClass farReferenceClass;

  private final Actor  actor;
  private final Object value;
  private Object databaseRef;

  public SFarReference(final Actor actor, final Object value) {
    this.actor = actor;
    this.value = value;
    assert !(value instanceof SFarReference);
    assert !(TransferObject.isTransferObject(value));
  }

  public Actor getActor() {
    return actor;
  }

  /**
   * @return the object the far reference is pointing to
   */
  public Object getValue() {
    return value;
  }

  @Override
  public SClass getSOMClass() {
    return farReferenceClass;
  }

  @Override
  public String toString() {
    return "FarRef[" + value.toString() + ", " + actor.toString() + "]";
  }

  @Override
  public boolean isValue() {
    return true;
  }

  public static void setSOMClass(final SClass cls) {
    assert farReferenceClass == null || cls == null;
    farReferenceClass = cls;
  }

  public Object getDatabaseRef() {
    return databaseRef;
  }

  public void setDatabaseRef(final Object databaseRef) {
    this.databaseRef = databaseRef;
  }

  public void storeInDb(final Database database, final Session session) {
    database.storeSFarReference(session, this);
  }
}
