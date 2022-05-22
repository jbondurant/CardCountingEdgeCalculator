import java.util.Objects;

public class TripleRanks {
    public Rank r1;
    public Rank r2;
    public Rank r3;

    public TripleRanks(Rank a, Rank b, Rank c){
        r1 = a;
        r2 = b;
        r3 = c;
    }

    public String getAsString(){
        String s = r1.name() + "&" + r2.name() + "&" + r3.name();
        return s;
    }

    public TripleRanks getFromString(String s){
        String[] parts = s.split("&");
        Rank a = Rank.valueOf(parts[0]);
        Rank b = Rank.valueOf(parts[1]);
        Rank c = Rank.valueOf(parts[2]);
        return new TripleRanks(a, b, c);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TripleRanks that = (TripleRanks) o;
        return r1 == that.r1 && r2 == that.r2 && r3 == that.r3;
    }

    @Override
    public int hashCode() {
        return Objects.hash(r1, r2, r3);
    }

}
