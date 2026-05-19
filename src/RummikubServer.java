
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

// 定義 WebSocket 的連線網址為 ws://localhost:8080/專案名稱/rummikub
@ServerEndpoint("/rummikub")
public class RummikubServer {

    private static Set<Session> allSessions = Collections.synchronizedSet(new HashSet<>());
    private static Set<Session> waitingQueue = Collections.synchronizedSet(new HashSet<>());
    private static ConcurrentHashMap<String, GameRoom> activeRooms = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session) {
        allSessions.add(session);
        waitingQueue.add(session);
        
        System.out.println("[UNIX LOG] [CONNECT] 玩家連線成功! Session ID: " + session.getId());
        System.out.println("[UNIX LOG] [MATCH] 目前大廳等待配對人數: " + waitingQueue.size());

        checkAndCreateRoom();
    }

    private synchronized void checkAndCreateRoom() {
        if (waitingQueue.size() >= 2) {
            Session player1 = waitingQueue.iterator().next();
            waitingQueue.remove(player1);
            Session player2 = waitingQueue.iterator().next();
            waitingQueue.remove(player2);

            GameRoom newRoom = new GameRoom(player1, player2);
            activeRooms.put(player1.getId(), newRoom);
            activeRooms.put(player2.getId(), newRoom);

            System.out.println("[UNIX LOG] [ROOM_CREATED] 成功媒合對戰! 房間內玩家: " + player1.getId() + " vs " + player2.getId());

            try {
                player1.getBasicRemote().sendText("{\"action\":\"START\", \"identity\":\"PLAYER_A\", \"msg\":\"配對成功! 你是先手玩家A。\"}");
                player2.getBasicRemote().sendText("{\"action\":\"START\", \"identity\":\"PLAYER_B\", \"msg\":\"配對成功! 你是後手玩家B。\"}");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        System.out.println("[UNIX LOG] [MESSAGE_RECEIVED] 收到來自玩家 " + session.getId() + " 的資料: " + message);

        GameRoom room = activeRooms.get(session.getId());
        if (room == null) {
            try {
                session.getBasicRemote().sendText("{\"action\":\"ERROR\", \"msg\":\"你目前不在任何遊戲房間內!\"}");
            } catch (IOException e) {
                e.printStackTrace();
            }
            return;
        }

        Session opponent = room.getOpponent(session);
        if (opponent != null && opponent.isOpen()) {
            try {
                opponent.getBasicRemote().sendText(message);
                System.out.println("[UNIX LOG] [SYNC] 已成功將最新畫面同步給對手: " + opponent.getId());
            } catch (IOException e) {
                System.err.println("[UNIX LOG] [ERROR] 同步資料失敗: " + e.getMessage());
            }
        }
    }

    @OnClose
    public void onClose(Session session) {
        allSessions.remove(session);
        waitingQueue.remove(session);
        
        GameRoom room = activeRooms.get(session.getId());
        if (room != null) {
            Session opponent = room.getOpponent(session);
            if (opponent != null && opponent.isOpen()) {
                try {
                    opponent.getBasicRemote().sendText("{\"action\":\"OPPONENT_LEFT\", \"msg\":\"對手已離開遊戲，你獲得勝利!\"}");
                } catch (IOException e) {
                    e.printStackTrace();
                }
                activeRooms.remove(opponent.getId());
            }
            activeRooms.remove(session.getId());
        }
        System.out.println("[UNIX LOG] [DISCONNECT] 玩家已斷線. Session ID: " + session.getId());
    }

    @OnError
    public void onError(Throwable error) {
        System.err.println("[UNIX LOG] [EXCEPTION] 連線異常: " + error.getMessage());
    }

    // 內部對戰房間物件
    private static class GameRoom {
        private Session playerA;
        private Session playerB;

        public GameRoom(Session a, Session b) {
            this.playerA = a;
            this.playerB = b;
        }

        public Session getOpponent(Session myself) {
            if (myself.getId().equals(playerA.getId())) {
                return playerB;
            }
            return playerA;
        }
    }
}