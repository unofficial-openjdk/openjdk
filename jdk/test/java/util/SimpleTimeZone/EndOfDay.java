/*
 * @test
 * @bug 0000377
 * @summary SimpleTimeZone should accept start/end rules at end of day
 */

import java.util.Calendar;
import java.util.SimpleTimeZone;

public class EndOfDay
{
  public static void main(String[] args)
  {
    SimpleTimeZone stz;
    stz = new SimpleTimeZone(0, "End/Day",
			     Calendar.MARCH, -1, Calendar.FRIDAY,
			     24 * 60 * 60 * 1000,
			     Calendar.APRIL, 1, Calendar.THURSDAY,
			     24 * 60 * 60 * 1000,
			     3600000);
    System.err.println(stz);
  }
}
