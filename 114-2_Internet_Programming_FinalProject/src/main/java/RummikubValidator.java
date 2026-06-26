
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RummikubValidator {

    /**
     * 驗證整個桌面狀態是否合法
     * @param boardState 前端傳回的 6x15 二維陣列 (null 代表空格)
     * @param isFirstMeld 該玩家是否尚未破冰 (如果是 true，會啟動 30 分破冰檢查)
     * @return 驗證結果物件 (包含是否成功與錯誤訊息)
     */
    public static ValidationResult validateBoard(Card[][] boardState, boolean isFirstMeld) {
        int totalRows = boardState.length;
        int totalCols = boardState[0].length;
        
        int newPlacedScore = 0; // 紀錄本回合新出牌的總分（破冰）
        boolean hasNewCard = false;

        // 逐行切分牌組
        for (int r = 0; r < totalRows; r++) {
            List<Card> currentGroup = new ArrayList<>();
            
            for (int c = 0; c < totalCols; c++) {
                Card card = boardState[r][c];
                
                if (card != null) {
                    currentGroup.add(card);
                    // 計算新放上去的牌的分數
                    if (card.isNew()) {
                        hasNewCard = true;
                    }
                }
                
                // 當遇到空格，或是到了該行的結尾時，開始驗證剛剛累積的連續牌組
                if (card == null || c == totalCols - 1) {
                    if (!currentGroup.isEmpty()) {
                        // 規則 1：每組牌至少需 3 張牌
                        if (currentGroup.size() < 3) {
                            return new ValidationResult(false, "第 " + (r+1) + " 排有牌組少於 3 張牌！");
                        }
                        
                        // 驗證這組牌是否為合法的 順組 或 群組
                        if (!isValidRun(currentGroup) && !isValidGroup(currentGroup)) {
                            return new ValidationResult(false, "第 " + (r+1) + " 排的牌組不符合順組或群組規則！");
                        }
                        
                        // 如果這組牌合法，且玩家正在嘗試破冰，計算這組牌中「新牌」所佔的實質點數
                        if (isFirstMeld) {
                            newPlacedScore += calculateNewCardsScoreInSet(currentGroup);
                        }
                        
                        currentGroup.clear(); // 清空，準備搜集下一組
                    }
                }
            }
        }

        // 破冰規則檢查
        if (isFirstMeld && hasNewCard) {
            if (newPlacedScore < 30) {
                return new ValidationResult(false, "破冰失敗！你出的新牌總和小於 30 分（目前：" + newPlacedScore + " 分）。");
            }
        }

        return new ValidationResult(true, "驗證成功！");
    }

    
    //檢查是否為合法群組 ：數字相同、顏色不同
     
    private static boolean isValidGroup(List<Card> set) {
        if (set.size() > 4) return false; // 群組最多 4 張牌（四種顏色）

        int targetNumber = -1;
        Set<String> colors = new HashSet<>();

        // 找出牌裡明確的數字（排除 Joker）
        for (Card card : set) {
            if (!card.isJoker()) {
                if (targetNumber == -1) {
                    targetNumber = card.getNumber();
                } else if (card.getNumber() != targetNumber) {
                    return false; // 數字不同，不為群組
                }
                
                // 檢查顏色是否重複
                if (colors.contains(card.getColor())) {
                    return false; // 同一個群組內不能有重複顏色的數字牌
                }
                colors.add(card.getColor());
            }
        }
        return true; // 全是 Joker，或符合同數異色
    }

    
    //檢查是否為合法順組 ：同顏色、連續數字
    private static boolean isValidRun(List<Card> set) {
        String targetColor = null;
        
        // 檢查顏色是否一致（排除 Joker）
        for (Card card : set) {
            if (!card.isJoker()) {
                if (targetColor == null) {
                    targetColor = card.getColor();
                } else if (!card.getColor().equals(targetColor)) {
                    return false; // 顏色不一致，不為順組
                }
            }
        }

        // 驗證數字是否連續（包含 Joker 的遞補邏輯）
        // 因為拉密桌面不能隨意調換格子順序，前端放好的一定是按順序排好的格網
        int expectedNumber = -1;
        for (int i = 0; i < set.size(); i++) {
            Card card = set.get(i);
            
            if (!card.isJoker()) {
                if (expectedNumber == -1) {
                    expectedNumber = card.getNumber();
                } else if (card.getNumber() != expectedNumber) {
                    return false; // 數字不連續！
                }
            }
            
            // 預期下一張牌的數字應該 +1
            if (expectedNumber != -1) {
                expectedNumber++;
                if (expectedNumber > 14) return false; // 超過數字 13 邊界
            }
        }
        
        // 逆向再推導一次，防止 Joker 放在開頭時導出非法的 0 或負數
        expectedNumber = -1;
        for (int i = set.size() - 1; i >= 0; i--) {
            Card card = set.get(i);
            if (!card.isJoker()) {
                expectedNumber = card.getNumber();
            }
            if (expectedNumber != -1) {
                expectedNumber--;
                if (expectedNumber < 0) return false; // Joker 不能代表 0 或負數
            }
        }

        return true;
    }

    //計算一組合法牌組中，「新放上去的牌」實質代表的分數（包含鬼牌的代入計算）
    private static int calculateNewCardsScoreInSet(List<Card> set) {
        int totalNewScore = 0;

        // 如果是群組：Joker 的點數等於其他標準牌的數字
        if (isValidGroup(set)) {
            int groupNum = 1;
            for (Card c : set) {
                if (!c.isJoker()) groupNum = c.getNumber();
            }
            for (Card c : set) {
                if (c.isNew()) totalNewScore += groupNum;
            }
        } 
        // 如果是順組：Joker 的點數必須依據當前位置去推算
        else if (isValidRun(set)) {
            int baseNum = -1;
            int baseIdx = -1;
            
            // 找到第一個非 Joker 的標準牌作為基準點
            for (int i = 0; i < set.size(); i++) {
                if (!set.get(i).isJoker()) {
                    baseNum = set.get(i).getNumber();
                    baseIdx = i;
                    break;
                }
            }
            
            // 根據基準點，推算出整排每一格牌的實質點數
            for (int i = 0; i < set.size(); i++) {
                int realNumber = baseNum - baseIdx + i;
                if (set.get(i).isNew()) {
                    totalNewScore += realNumber;
                }
            }
        }
        return totalNewScore;
    }

    //用於打包回傳結果的輔助類別
    public static class ValidationResult {
        private boolean success;
        private String message;

        public ValidationResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
    }

    // --- 測試用 Main 函式 ---
    // public static void main(String[] args) {
    //     // 模擬 6x15 網格
    //     Card[][] mockBoard = new Card[6][15];

    //     // 測試案例：放一組新出的 順組 紅 10, 紅 11, Joker (代替紅 12) = 33 分
    //     Card c1 = new Card(1, "RED", 10);  c1.setNew(true);
    //     Card c2 = new Card(2, "RED", 11);  c2.setNew(true);
    //     Card c3 = new Card(105, "JOKER");  c3.setNew(true); // 實質代表 12 分

    //     mockBoard[0][0] = c1;
    //     mockBoard[0][1] = c2;
    //     mockBoard[0][2] = c3;

    //     // 執行驗證（假設是首次破冰）
    //     ValidationResult result = validateBoard(mockBoard, true);
    //     System.out.println("驗證結果：" + result.isSuccess()); // 應為 true
    //     System.out.println("回應訊息：" + result.getMessage());  // 驗證成功！
    // }

    // --- 完整功能壓力測試 Main 函式 ---
    public static void main(String[] args) {
        System.out.println("====== 🧪 開始拉密驗證演算法壓力測試 ======");

        // ----------------------------------------------------
        // 測試案例 1：完全合法的桌面（包含一組順組、一組群組，且點數大於30分）
        // ----------------------------------------------------
        Card[][] board1 = new Card[6][15];
        // 順組：黑 7, 黑 8, 黑 9 (24分)
        board1[0][0] = createMockCard(1, "BLACK", 7, true);
        board1[0][1] = createMockCard(2, "BLACK", 8, true);
        board1[0][2] = createMockCard(3, "BLACK", 9, true);
        
        // 群組：紅 10, 藍 10, 黃 10 (30分)
        board1[1][5] = createMockCard(4, "RED", 10, true);
        board1[1][6] = createMockCard(5, "BLUE", 10, true);
        board1[1][7] = createMockCard(6, "YELLOW", 10, true);

        ValidationResult r1 = validateBoard(board1, true); // 第一次出牌（破冰）
        System.out.println("測試 1 (合法破冰 54 分) -> 預期: true");
        System.out.println("實際結果: " + r1.isSuccess() + " | 訊息: " + r1.getMessage());
        System.out.println("------------------------------------------------");

        // ----------------------------------------------------
        // 測試案例 2：非法的群組（群組內有重複顏色的牌，例如：紅 7, 藍 7, 紅 7）
        // ----------------------------------------------------
        Card[][] board2 = new Card[6][15];
        board2[0][0] = createMockCard(7, "RED", 7, true);
        board2[0][1] = createMockCard(8, "BLUE", 7, true);
        board2[0][2] = createMockCard(9, "RED", 7, true); // 重複紅色！

        ValidationResult r2 = validateBoard(board2, false); // 已破冰狀態
        System.out.println("測試 2 (異色同號群組出錯) -> 預期: false");
        System.out.println("實際結果: " + r2.isSuccess() + " | 訊息: " + r2.getMessage());
        System.out.println("------------------------------------------------");

        // ----------------------------------------------------
        // 測試案例 3：非法的順組（數字不連續，例如：藍 4, 藍 5, 藍 7）
        // ----------------------------------------------------
        Card[][] board3 = new Card[6][15];
        board3[0][0] = createMockCard(10, "BLUE", 4, true);
        board3[0][1] = createMockCard(11, "BLUE", 5, true);
        board3[0][2] = createMockCard(12, "BLUE", 7, true); // 跳過了 6！

        ValidationResult r3 = validateBoard(board3, false);
        System.out.println("測試 3 (同色連號數字中斷) -> 預期: false");
        System.out.println("實際結果: " + r3.isSuccess() + " | 訊息: " + r3.getMessage());
        System.out.println("------------------------------------------------");

        // ----------------------------------------------------
        // 測試案例 4：百搭牌 Joker 破冰測試
        // 順組：紅 9, Joker(代表10), 紅 11 = 新牌總分 30 分，剛好擦邊破冰
        // ----------------------------------------------------
        Card[][] board4 = new Card[6][15];
        board4[2][0] = createMockCard(13, "RED", 9, true);
        board4[2][1] = createMockJoker(105, true); // Joker 補在中間
        board4[2][2] = createMockCard(14, "RED", 11, true);

        ValidationResult r4 = validateBoard(board4, true); // 嘗試破冰
        System.out.println("測試 4 (Joker精準破冰 30 分) -> 預期: true");
        System.out.println("實際結果: " + r4.isSuccess() + " | 訊息: " + r4.getMessage());
        System.out.println("================================================");
    }

    // 輔助建立測試卡牌的小工具
    private static Card createMockCard(int id, String color, int num, boolean isNew) {
        Card c = new Card(id, color, num);
        c.setNew(isNew);
        return c;
    }

    // 輔助建立測試百搭牌的小工具
    private static Card createMockJoker(int id, boolean isNew) {
        Card c = new Card(id, "JOKER");
        c.setNew(isNew);
        return c;
    }
}