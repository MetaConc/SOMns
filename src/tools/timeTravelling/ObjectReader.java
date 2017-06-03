package tools.timeTravelling;

import static tools.timeTravelling.Database.getDatabaseInstance;

import org.neo4j.driver.v1.Session;

import som.vmobjects.SSymbol;

public  class ObjectReader {

  public static long readMessage(final long actorId, final long causalMessageId) {
    try {

      Database database = getDatabaseInstance();
      Session session = database.startSession();

      SSymbol messageName = database.readMessageName(session, actorId, causalMessageId);
      Object[] arguments = database.readMessageArguments(session, causalMessageId);
      System.out.println(messageName+ ": " + arguments);
      database.endSession(session);
      return 0;
    } finally {

    }
  }
}
