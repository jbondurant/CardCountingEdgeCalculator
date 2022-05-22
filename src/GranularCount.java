import java.util.Objects;

public class GranularCount implements Comparable<GranularCount> {
    int units;
    int firstDecimal;
    int secondDecimal;

    public String countToCellString(){
        String result = "";
        result += units + "." + firstDecimal + secondDecimal;
        return result;
    }

    public String getStringFromCount(){
        String result = "";
        result += units + "&";
        result += firstDecimal + "&";
        result += secondDecimal;
        return result;
    }

    public double getDoubleFromCount(){
        double d = 0.0;
        d += units;
        d += ((double) firstDecimal) * 0.1;
        d += ((double) secondDecimal) * 0.01;
        return d;
    }

    public boolean isCountInBoundaries(int minC, int maxC){
        if(units < minC || units > maxC){
            return false;
        }
        if(units == minC || units == maxC){
            if(firstDecimal !=0 || secondDecimal != 0){
                return false;
            }
        }
        return true;
    }

    public static GranularCount getCountFromString(String s){
        String[] parts = s.split("&");
        int u = Integer.parseInt(parts[0]);
        int fd = Integer.parseInt(parts[1]);
        int sd = Integer.parseInt(parts[2]);
        return new GranularCount(u, fd, sd);
    }

    public GranularCount(int u, int fd, int sd){
        units = u;
        firstDecimal = fd;
        secondDecimal = sd;
    }

    public GranularCount(double count){
        units = (int) count;
        firstDecimal = (int) ((count - units) * 10.0);
        secondDecimal = (int) ((((count - units) * 10.0) - firstDecimal) * 10.0);
    }

    public static void main (String[] args){
        double a = 3.512;
        GranularCount gc = new GranularCount(a);
        GranularCount gc2 = new GranularCount(roundToGrain(a, 0.25));
        int g=1;
    }

    public static double roundToGrain(double d, double grain) {
        double invG = 1.0 / grain;
        return (Math.round(d * invG) / invG);
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GranularCount that = (GranularCount) o;
        return units == that.units && firstDecimal == that.firstDecimal && secondDecimal == that.secondDecimal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(units, firstDecimal, secondDecimal);
    }


    @Override
    public int compareTo(GranularCount gc2) {
        if(this.units == gc2.units){
            if(this.firstDecimal == gc2.firstDecimal){
                if(this.secondDecimal == gc2.secondDecimal){
                    return 0;
                }
                return this.secondDecimal - gc2.secondDecimal;
            }
            return this.firstDecimal - gc2.firstDecimal;
        }
        return this.units - gc2.units;
    }
}
