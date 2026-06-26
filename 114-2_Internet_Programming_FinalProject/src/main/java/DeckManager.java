
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DeckManager {
    private List<Card> deck; // 整副牌堆

    public DeckManager() {
        deck = new ArrayList<>();
        initializeDeck();
    }

    // 初始化 106 張拉密牌
    private void initializeDeck() {
        String[] colors = { "RED", "BLUE", "YELLOW", "BLACK" };
        int currentId = 1;

        // 每種顏色、每個數字都有 2 張
        for (int set = 1; set <= 2; set++) {
            for (String color : colors) {
                for (int num = 1; num <= 13; num++) {
                    deck.add(new Card(currentId, color, num));
                    currentId++;
                }
            }
        }

        // 加入 2 張黑色的百搭牌
        deck.add(new Card(currentId, "JOKER")); // ID: 105
        currentId++;
        deck.add(new Card(currentId, "JOKER")); // ID: 106
    }

    // 洗牌功能
    public void shuffleDeck() {
        // 利用 Java 內建的 Collections.shuffle 進行隨機打亂
        Collections.shuffle(deck);
    }

    // 發牌功能 (每人 14 張牌)
    public List<Card> drawHand() {
        List<Card> hand = new ArrayList<>();
        // 從牌堆頂端拿走 14 張牌
        for (int i = 0; i < 14; i++) {
            if (!deck.isEmpty()) {
                hand.add(deck.remove(0)); // 移除並加入手牌
            }
        }
        return hand;
    }

    // 摸牌功能 
    public Card drawCard() {
        if (!deck.isEmpty()) {
            return deck.remove(0);
        }
        return null; 
    }

    // 獲取目前牌堆剩餘張數
    public int getRemainingCount() {
        return deck.size();
    }

    // 測試用
    public static void main(String[] args) {
        DeckManager manager = new DeckManager();
        System.out.println("牌組初始化完成，總張數：" + manager.getRemainingCount()); 

        manager.shuffleDeck();
        System.out.println("洗牌完成！");

        List<Card> playerAHand = manager.drawHand();
        System.out.println("玩家 A 的初始手牌 (14張)：" + playerAHand);
        System.out.println("剩餘牌堆張數：" + manager.getRemainingCount());
    }

}
