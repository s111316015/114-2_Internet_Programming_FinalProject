

public class Card {
    private int id;         // 1 ~ 106 的唯一識別碼
    private String color;   // "RED", "BLUE", "YELLOW", "BLACK", "JOKER"
    private int number;     // 1 ~ 13 (如果是 JOKER 可以設為 0)
    private boolean isJoker;// 是否為百搭牌

    // 標準牌的建構子
    public Card(int id, String color, int number) {
        this.id = id;
        this.color = color;
        this.number = number;
        this.isJoker = false;
    }

    // 百搭牌的建構子
    public Card(int id, String color) {
        this.id = id;
        this.color = color; // "JOKER"
        this.number = 0;
        this.isJoker = true;
    }

    // --- Getters 和 Setters ---
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public int getNumber() { return number; }
    public void setNumber(int number) { this.number = number; }

    public boolean isJoker() { return isJoker; }
    public void setJoker(boolean joker) { isJoker = joker; }

    // 在 Card.java 中增補以下欄位與 Getter/Setter
private boolean isNew; 
public boolean isNew() { return isNew; }
public void setNew(boolean isNew) { this.isNew = isNew; }

    // 方便你在 Unix 終端機或 Console 測試排錯時列印查看
    @Override
    public String toString() {
        if (isJoker) {
            return "[Joker (ID:" + id + ")]";
        }
        return "[" + color + " " + number + " (ID:" + id + ")]";
    }
}