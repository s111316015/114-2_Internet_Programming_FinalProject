import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

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

            // 📢 問題2：開局時為先後手各自抽出隨機的 14 張牌
            List<Card> handA = newRoom.getDeckManager().drawHand();
            List<Card> handB = newRoom.getDeckManager().drawHand();

            try {
                String startJsonA = String.format(
                    "{\"action\":\"START\", \"identity\":\"PLAYER_A\", \"msg\":\"配對成功! 你是先手玩家A。\", \"hand\":%s, \"boardState\":%s, \"deckRemaining\":%d, \"opponentCardCount\":14}",
                    cardsToJson(handA), boardToJson(newRoom.getBoardState()), newRoom.getDeckManager().getRemainingCount()
                );
                
                // ✨ 這裡已修正：精準補齊 boardToJson(newRoom.getBoardState()) 與剩餘張數參數
                String startJsonB = String.format(
                    "{\"action\":\"START\", \"identity\":\"PLAYER_B\", \"msg\":\"配對成功! 你是後手玩家B。\", \"hand\":%s, \"boardState\":%s, \"deckRemaining\":%d, \"opponentCardCount\":14}",
                    cardsToJson(handB), boardToJson(newRoom.getBoardState()), newRoom.getDeckManager().getRemainingCount()
                );

                player1.getBasicRemote().sendText(startJsonA);
                player2.getBasicRemote().sendText(startJsonB);
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

        String action = extractJsonValue(message, "action");
        Session opponent = room.getOpponent(session);

        // 📢 問題4 & 5：處理主动摸牌與回合結束
        if ("DRAW_CARD".equals(action)) {
            Card drawn = room.getDeckManager().drawCard();
            room.addHandCount(session, 1);
            room.setCurrentTurn(opponent.getId()); // 摸牌代表無條件移交回合權

            try {
                session.getBasicRemote().sendText(String.format(
                    "{\"action\":\"DRAW_RESULT\", \"newCard\":%s, \"deckRemaining\":%d, \"opponentCardCount\":%d}",
                    cardToJson(drawn), room.getDeckManager().getRemainingCount(), room.getHandCount(opponent)
                ));

                if (opponent != null && opponent.isOpen()) {
                    opponent.getBasicRemote().sendText(String.format(
                        "{\"action\":\"SYNC_BOARD\", \"boardState\":%s, \"deckRemaining\":%d, \"opponentCardCount\":%d, \"isYourTurn\":true, \"msg\":\"對手選擇摸一張牌並結束回合。換你的回合！\"}",
                        boardToJson(room.getBoardState()), room.getDeckManager().getRemainingCount(), room.getHandCount(session)
                    ));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } 
        // 📢 問題5 & 6：結束回合（包含破冰點數與常規合法牌組驗證）
        else if ("SUBMIT_TURN".equals(action)) {
            Card[][] proposedBoard = parseBoardState(message);
            boolean isFirstMeld = session.getId().equals(room.getPlayerA().getId()) ? room.isPlayerAFirstMeld() : room.isPlayerBFirstMeld();

            // 呼叫驗證器檢查規則
            RummikubValidator.ValidationResult res = RummikubValidator.validateBoard(proposedBoard, isFirstMeld);

            try {
                if (res.isSuccess()) {
                    // 驗證成功：固化桌面狀態、消除 new 標記
                    room.setBoardState(proposedBoard);
                    for (int r = 0; r < 6; r++) {
                        for (int c = 0; c < 15; c++) {
                            if (room.getBoardState()[r][c] != null) {
                                room.getBoardState()[r][c].setNew(false);
                            }
                        }
                    }

                    // 標註該玩家已完成破冰
                    if (session.getId().equals(room.getPlayerA().getId())) room.setPlayerAFirstMeld(false);
                    else room.setPlayerBFirstMeld(false);

                    // 記錄玩家最新手牌數量
                    int handCount = Integer.parseInt(extractJsonValue(message, "opponentCardCount"));
                    if (session.getId().equals(room.getPlayerA().getId())) room.setPlayerAHandCount(handCount);
                    else room.setPlayerBHandCount(handCount);

                    room.setCurrentTurn(opponent.getId());

                    session.getBasicRemote().sendText(String.format(
                        "{\"action\":\"TURN_RESULT\", \"success\":true, \"msg\":\"%s\", \"boardState\":%s, \"deckRemaining\":%d, \"opponentCardCount\":%d}",
                        res.getMessage(), boardToJson(room.getBoardState()), room.getDeckManager().getRemainingCount(), room.getHandCount(opponent)
                    ));

                    if (opponent != null && opponent.isOpen()) {
                        opponent.getBasicRemote().sendText(String.format(
                            "{\"action\":\"SYNC_BOARD\", \"boardState\":%s, \"deckRemaining\":%d, \"opponentCardCount\":%d, \"isYourTurn\":true, \"msg\":\"對手出牌結束，換你的回合！\"}",
                            boardToJson(room.getBoardState()), room.getDeckManager().getRemainingCount(), room.getHandCount(session)
                        ));
                    }
                } else {
                    // 📢 問題6：驗證失敗，自動懲罰摸一張實體牌、回復原始棋盤殘局、強制結束回合
                    Card penaltyCard = room.getDeckManager().drawCard();
                    room.addHandCount(session, 1);
                    room.setCurrentTurn(opponent.getId());

                    session.getBasicRemote().sendText(String.format(
                        "{\"action\":\"TURN_RESULT\", \"success\":false, \"msg\":\"%s\", \"newCard\":%s, \"boardState\":%s, \"deckRemaining\":%d, \"opponentCardCount\":%d}",
                        res.getMessage(), cardToJson(penaltyCard), boardToJson(room.getBoardState()), room.getDeckManager().getRemainingCount(), room.getHandCount(opponent)
                    ));

                    if (opponent != null && opponent.isOpen()) {
                        opponent.getBasicRemote().sendText(String.format(
                            "{\"action\":\"SYNC_BOARD\", \"boardState\":%s, \"deckRemaining\":%d, \"opponentCardCount\":%d, \"isYourTurn\":true, \"msg\":\"對手出牌驗證失敗！自動回復原狀並遭罰摸一張牌。換你的回合！\"}",
                            boardToJson(room.getBoardState()), room.getDeckManager().getRemainingCount(), room.getHandCount(session)
                        ));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
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

    // --- 🛠️ 輕量原生 JSON 工具函式組 ---
    private static String cardsToJson(List<Card> cards) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < cards.size(); i++) {
            sb.append(cardToJson(cards.get(i)));
            if (i < cards.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String cardToJson(Card c) {
        if (c == null) return "null";
        return String.format("{\"id\":%d,\"color\":\"%s\",\"number\":%d,\"isJoker\":%b,\"isNew\":%b}", 
            c.getId(), c.getColor(), c.getNumber(), c.isJoker(), c.isNew());
    }

    private static String boardToJson(Card[][] board) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int r = 0; r < board.length; r++) {
            sb.append("[");
            for (int c = 0; c < board[r].length; c++) {
                sb.append(cardToJson(board[r][c]));
                if (c < board[r].length - 1) sb.append(",");
            }
            sb.append("]");
            if (r < board.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String extractJsonValue(String json, String key) {
        int keyIdx = json.indexOf("\"" + key + "\":");
        if (keyIdx == -1) return "";
        int start = keyIdx + key.length() + 3;
        int commaIdx = json.indexOf(",", start);
        int braceIdx = json.indexOf("}", start);
        int end = commaIdx;
        if (end == -1 || (braceIdx != -1 && braceIdx < end)) end = braceIdx;
        if (end == -1) return "";
        String val = json.substring(start, end).trim();
        if (val.startsWith("\"") && val.endsWith("\"")) {
            val = val.substring(1, val.length() - 1);
        }
        return val;
    }

    private static Card[][] parseBoardState(String json) {
        Card[][] board = new Card[6][15];
        int startIdx = json.indexOf("\"boardState\":");
        if (startIdx == -1) return board;
        
        String boardStr = json.substring(startIdx);
        int openBracket = boardStr.indexOf("[");
        int closeBracket = boardStr.lastIndexOf("]");
        if (openBracket == -1 || closeBracket == -1) return board;
        boardStr = boardStr.substring(openBracket + 1, closeBracket);
        
        List<String> rows = new ArrayList<>();
        int bracketCount = 0;
        StringBuilder sb = new StringBuilder();
        for (char c : boardStr.toCharArray()) {
            if (c == '[') {
                bracketCount++;
                if (bracketCount == 1) continue;
            }
            if (c == ']') {
                bracketCount--;
                if (bracketCount == 0) {
                    rows.add(sb.toString());
                    sb.setLength(0);
                    continue;
                }
            }
            if (bracketCount > 0) {
                sb.append(c);
            }
        }
        
        for (int r = 0; r < Math.min(6, rows.size()); r++) {
            String rowText = rows.get(r);
            List<String> cellsText = new ArrayList<>();
            int cellBracketCount = 0;
            StringBuilder cellSb = new StringBuilder();
            for (char c : rowText.toCharArray()) {
                if (c == '{') cellBracketCount++;
                if (c == '}') cellBracketCount--;
                cellSb.append(c);
                if (cellBracketCount == 0 && c == ',') {
                    String txt = cellSb.toString().trim();
                    if (txt.endsWith(",")) txt = txt.substring(0, txt.length() - 1).trim();
                    cellsText.add(txt);
                    cellSb.setLength(0);
                }
            }
            if (cellSb.length() > 0) {
                cellsText.add(cellSb.toString().trim());
            }
            
            for (int c = 0; c < Math.min(15, cellsText.size()); c++) {
                String cellTxt = cellsText.get(c);
                if (cellTxt.equals("null") || cellTxt.isEmpty()) {
                    board[r][c] = null;
                } else {
                    try {
                        int id = Integer.parseInt(extractJsonValue(cellTxt, "id"));
                        String color = extractJsonValue(cellTxt, "color");
                        int number = Integer.parseInt(extractJsonValue(cellTxt, "number"));
                        boolean isNew = Boolean.parseBoolean(extractJsonValue(cellTxt, "isNew"));
                        
                        Card card = new Card(id, color, number);
                        card.setNew(isNew);
                        board[r][c] = card;
                    } catch (Exception e) {
                        board[r][c] = null;
                    }
                }
            }
        }
        return board;
    }

    // --- 內部對戰房間物件狀態追蹤 ---
    private static class GameRoom {
        private Session playerA;
        private Session playerB;
        private DeckManager deckManager;
        private Card[][] boardState;
        private boolean playerAFirstMeld = true;
        private boolean playerBFirstMeld = true;
        private int playerAHandCount = 14;
        private int playerBHandCount = 14;
        private String currentTurn = "PLAYER_A";

        public GameRoom(Session a, Session b) {
            this.playerA = a;
            this.playerB = b;
            this.deckManager = new DeckManager();
            this.deckManager.shuffleDeck();
            this.boardState = new Card[6][15];
        }

        public Session getPlayerA() { return playerA; }
        public Session getPlayerB() { return playerB; }
        public DeckManager getDeckManager() { return deckManager; }
        public Card[][] getBoardState() { return boardState; }
        public void setBoardState(Card[][] board) { this.boardState = board; }
        public boolean isPlayerAFirstMeld() { return playerAFirstMeld; }
        public void setPlayerAFirstMeld(boolean val) { this.playerAFirstMeld = val; }
        public boolean isPlayerBFirstMeld() { return playerBFirstMeld; }
        public void setPlayerBFirstMeld(boolean val) { this.playerBFirstMeld = val; }
        
        public int getHandCount(Session s) {
            return s.getId().equals(playerA.getId()) ? playerAHandCount : playerBHandCount;
        }
        public void addHandCount(Session s, int val) {
            if (s.getId().equals(playerA.getId())) playerAHandCount += val;
            else playerBHandCount += val;
        }
        public void setPlayerAHandCount(int val) { this.playerAHandCount = val; }
        public void setPlayerBHandCount(int val) { this.playerBHandCount = val; }
        public String getCurrentTurn() { return currentTurn; }
        public void setCurrentTurn(String turn) { this.currentTurn = turn; }

        public Session getOpponent(Session myself) {
            if (myself.getId().equals(playerA.getId())) return playerB;
            return playerA;
        }
    }
}