package tools.timeTravelling;

import static tools.timeTravelling.Database.getDatabaseInstance;

import java.util.HashMap;
import java.util.Map;

import org.neo4j.driver.v1.Session;

import som.VM;
import som.vmobjects.SAbstractObject;
import som.vmobjects.SSymbol;

public  class ObjectReader {
/*
 * map keeps track of revived SObject
 * This is necessary as we want to preserve the object state
 * Two objects pointing to the same third object should again point to the same object
 */
  private static Map<Object, SAbstractObject> revivedObjects = new HashMap<Object, SAbstractObject>();

  public static void readMessage(final long actorId, final long causalMessageId) {
    try {

      Database database = getDatabaseInstance();
      Session session = database.startSession();

      SSymbol messageName = database.readMessageName(session, actorId, causalMessageId);
      Object[] arguments = database.readMessageArguments(session, causalMessageId);
      SAbstractObject target = database.readTarget(session, causalMessageId);
      arguments[0] = target;
      database.endSession(session);

      TimeTravellingDebugger.replay(messageName, target, arguments);
    } catch (Exception e) {
      VM.errorPrint(e.getMessage());
      e.printStackTrace();
    }
  }


  public static synchronized void reportSAbstractObject(final Object dbRef, final SAbstractObject object) {
    revivedObjects.put(dbRef, object);
  }

  public static synchronized SAbstractObject getSAbstractObject(final Object dbRef) {
    return revivedObjects.get(dbRef);
  }
}
