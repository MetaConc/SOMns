package tools.timeTravelling;

import static tools.timeTravelling.Database.getDatabaseInstance;

import org.neo4j.driver.v1.Session;

import som.VM;
import som.vmobjects.SAbstractObject;
import som.vmobjects.SSymbol;

public  class ObjectReader {

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
}
