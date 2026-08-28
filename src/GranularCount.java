import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Random;

public class GranularCount implements Comparable<GranularCount> {
    int units;
    int firstDecimal;
    int secondDecimal;

    public static int numGranCount(double countPrecision, int minC, int maxC){
        double interval = (double) (maxC - minC);
        return (int) (interval / countPrecision + 1.0);
    }

    public static GranularCount getRandomCount(int minC, int maxC, double countPrecision){
        ArrayList<GranularCount> allCounts = new ArrayList<>();
        double currentCount = minC;
        while(currentCount <= maxC){
            GranularCount gc = new GranularCount(currentCount);
            allCounts.add(gc);
            currentCount += countPrecision;
        }
        Random rand = new Random();
        GranularCount randomCount = allCounts.get(rand.nextInt(allCounts.size()));
        return randomCount;
    }

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

    public boolean forceCountIntoBoundaries(int minC, int maxC){
        if(units < minC){
            units = minC;
            firstDecimal = 0;
            secondDecimal = 0;
        }
        if(units > maxC){
            units = maxC;
            firstDecimal = 0;
            secondDecimal = 0;
        }
        if(units == minC || units == maxC){
            if(firstDecimal !=0 || secondDecimal != 0){
                firstDecimal = 0;
                secondDecimal = 0;
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

    /**
     * Snap a raw true count onto the count grid, breaking ties toward zero.
     *
     * Two things are going on here.
     *
     * First, Math.round breaks ties toward positive infinity, so Math.round(1.5) is 2
     * while Math.round(-1.5) is -1. Hi-Lo is a balanced count, so that hands every
     * half-way count to the positive bucket and skews the histogram. The true count is
     * one integer over another and most deck-counts are even, so exact .5 ties are about
     * 9% of hands rather than a rare edge case.
     *
     * Second, of the tie rules that are symmetric about zero, which one to use is not
     * arbitrary. The true count is densest near zero, so a bucket [k-0.5, k+0.5] holds
     * more mass at its low edge than its high edge. Breaking ties away from zero pulls
     * the dense low tie in and pushes the sparse high tie out, dragging each bucket's
     * mean below its own label by about 0.11 and compressing the whole edge-vs-count
     * curve. Breaking ties toward zero does the reverse and very nearly cancels that
     * density asymmetry, leaving a residual of about 0.02.
     *
     * CountBiasReport prints the comparison, including half-to-even, which is symmetric
     * but makes bucket populations alternate.
     */
    public static double roundToGrain(double d, double grain) {
        double invG = 1.0 / grain;
        double scaled = d * invG;
        double rounded = Math.signum(scaled) * Math.ceil(Math.abs(scaled) - 0.5);
        return rounded / invG;
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
