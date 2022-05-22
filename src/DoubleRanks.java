import java.util.Objects;

public class DoubleRanks {
    public Rank r1;
    public Rank r2;

    public DoubleRanks(Rank a, Rank b){
        r1 = a;
        r2 = b;
    }

    public String getAsString(){
        String s = r1.name() + "&" + r2.name();
        return s;
    }

    public DoubleRanks getFromString(String s){
        String[] parts = s.split("&");
        Rank a = Rank.valueOf(parts[0]);
        Rank b = Rank.valueOf(parts[1]);
        return new DoubleRanks(a, b);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DoubleRanks that = (DoubleRanks) o;
        return r1 == that.r1 && r2 == that.r2;
    }

    @Override
    public int hashCode() {
        return Objects.hash(r1, r2);
    }
}
