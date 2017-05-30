package tools.timeTravelling;

import static tools.timeTravelling.Database.getDatabaseInstance;

import org.neo4j.driver.v1.Session;

public  class ObjectReader {

  public static long readMessage(final long actorId, final long messageId) {
    try {

      Database database = getDatabaseInstance();
      Session session = database.startSession();

      database.getClassOfTurn(session, actorId, messageId);

      database.endSession(session);
      return 0;
    } finally {

    }
  }
}
