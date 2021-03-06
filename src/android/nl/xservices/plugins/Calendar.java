package nl.xservices.plugins;

import java.util.Date;

import nl.xservices.plugins.accessor.AbstractCalendarAccessor;
import nl.xservices.plugins.accessor.CalendarProviderAccessor;
import nl.xservices.plugins.accessor.LegacyCalendarAccessor;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

public class Calendar extends CordovaPlugin {
  public static final String ACTION_CREATE_EVENT = "createEvent";
  public static final String ACTION_CREATE_EVENT_WITH_OPTIONS = "createEventWithOptions";
  public static final String ACTION_CREATE_EVENT_INTERACTIVELY = "createEventInteractively";
  public static final String ACTION_DELETE_EVENT = "deleteEvent";
  public static final String ACTION_FIND_EVENT = "findEvent";
  public static final String ACTION_LIST_EVENTS_IN_RANGE = "listEventsInRange";
  public static final String ACTION_LIST_CALENDARS = "listCalendars";

  public static final Integer RESULT_CODE_CREATE = 0;

  private CallbackContext callback;

  private static final String LOG_TAG = AbstractCalendarAccessor.LOG_TAG;
  
  private Integer existingNumberOfEvents = 0;
  
  private JSONArray eventRequestData;

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
    callback = callbackContext;
    // TODO this plugin may work fine on 3.0 devices, but have not tested it yet, so to be sure:
    final boolean hasLimitedSupport = Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH;
    if (ACTION_CREATE_EVENT.equals(action) || ACTION_CREATE_EVENT_WITH_OPTIONS.equals(action)) {
      if (hasLimitedSupport) {
        // TODO investigate this option some day: http://stackoverflow.com/questions/3721963/how-to-add-calendar-events-in-android
        return createEventInteractively(args);
      } else {
        return createEvent(args);
      }
    } else if (ACTION_CREATE_EVENT_INTERACTIVELY.equals(action)) {
      return createEventInteractively(args);
    } else if (ACTION_LIST_EVENTS_IN_RANGE.equals(action)) {
      return listEventsInRange(args);
    } else if (!hasLimitedSupport && ACTION_FIND_EVENT.equals(action)) {
      return findEvents(args);
    } else if (!hasLimitedSupport && ACTION_DELETE_EVENT.equals(action)) {
      return deleteEvent(args);
    } else if (!hasLimitedSupport && ACTION_LIST_CALENDARS.equals(action)) {
      return listCalendars();
    }
    return false;
  }

  private boolean listCalendars() throws JSONException {
    final JSONArray jsonObject = getCalendarAccessor().getActiveCalendars();
    PluginResult res = new PluginResult(PluginResult.Status.OK, jsonObject);
    callback.sendPluginResult(res);
    return true;
  }

  private boolean createEventInteractively(JSONArray args) throws JSONException {
    eventRequestData = new JSONArray();
    eventRequestData = args;
    final JSONObject jsonFilter = args.getJSONObject(0);
    final Intent calIntent = new Intent(Intent.ACTION_EDIT)
        .setType("vnd.android.cursor.item/event")
        .putExtra("title", jsonFilter.optString("title"))
        .putExtra("eventLocation", jsonFilter.optString("location"))
        .putExtra("description", jsonFilter.optString("notes"))
        .putExtra("beginTime", jsonFilter.optLong("startTime"))
        .putExtra("endTime", jsonFilter.optLong("endTime"))
        .putExtra("hasAlarm", 1)
        .putExtra("allDay", AbstractCalendarAccessor.isAllDayEvent(new Date(jsonFilter.optLong("startTime")), new Date(jsonFilter.optLong("endTime"))));
        // TODO can we pass a reminder here?
    existingNumberOfEvents = getEventCountInRange(args);
    this.cordova.startActivityForResult(this, calIntent, RESULT_CODE_CREATE);
    return true;
  }

  private AbstractCalendarAccessor calendarAccessor;

  private AbstractCalendarAccessor getCalendarAccessor() {
    if (this.calendarAccessor == null) {
      // Note: currently LegacyCalendarAccessor is never used, see the TODO at the top of this class
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
        Log.d(LOG_TAG, "Initializing calendar plugin");
        this.calendarAccessor = new CalendarProviderAccessor(this.cordova);
      } else {
        Log.d(LOG_TAG, "Initializing legacy calendar plugin");
        this.calendarAccessor = new LegacyCalendarAccessor(this.cordova);
      }
    }
    return this.calendarAccessor;
  }

  private boolean deleteEvent(JSONArray args) {
    if (args.length() == 0) {
      System.err.println("Exception: No Arguments passed");
    } else {
      try {
        JSONObject jsonFilter = args.getJSONObject(0);
        boolean deleteResult = getCalendarAccessor().deleteEvent(
            null,
            jsonFilter.optLong("startTime"),
            jsonFilter.optLong("endTime"),
            jsonFilter.optString("title"),
            jsonFilter.optString("location"));
        PluginResult res = new PluginResult(PluginResult.Status.OK, deleteResult);
        res.setKeepCallback(true);
        callback.sendPluginResult(res);
        return true;
      } catch (JSONException e) {
        System.err.println("Exception: " + e.getMessage());
      }
    }
    return false;
  }

  private boolean findEvents(JSONArray args) {
    if (args.length() == 0) {
      System.err.println("Exception: No Arguments passed");
    }
    try {
      JSONObject jsonFilter = args.getJSONObject(0);
      JSONArray jsonEvents = getCalendarAccessor().findEvents(
          jsonFilter.optString("title"),
          jsonFilter.optString("location"),
          jsonFilter.optLong("startTime"),
          jsonFilter.optLong("endTime"));

      PluginResult res = new PluginResult(PluginResult.Status.OK, jsonEvents);
      res.setKeepCallback(true);
      callback.sendPluginResult(res);
      return true;

    } catch (JSONException e) {
      System.err.println("Exception: " + e.getMessage());
    }
    return false;
  }

  private boolean createEvent(JSONArray args) {
    try {
      final JSONObject argObject = args.getJSONObject(0);
      final JSONObject argOptionsObject = argObject.getJSONObject("options");

      boolean status = getCalendarAccessor().createEvent(null, argObject.getString("title"),
          argObject.getLong("startTime"), argObject.getLong("endTime"),
          argObject.getString("notes"), argObject.getString("location"),
          argOptionsObject.isNull("firstReminderMinutes") ? null : argOptionsObject.getLong("firstReminderMinutes"));

      callback.success("" + status);
      return true;
    } catch (Exception e) {
      System.err.println("Exception: " + e.getMessage());
    }
    return false;
  }

  private boolean listEventsInRange(JSONArray args) {
    try {
      Uri l_eventUri;
      if (Build.VERSION.SDK_INT >= 8) {
        l_eventUri = Uri.parse("content://com.android.calendar/events");
      } else {
        l_eventUri = Uri.parse("content://calendar/events");
      }
      ContentResolver contentResolver = this.cordova.getActivity().getContentResolver();
      JSONObject jsonFilter = args.getJSONObject(0);
      JSONArray result = new JSONArray();
      long input_start_date = jsonFilter.optLong("startTime");
      long input_end_date = jsonFilter.optLong("endTime");

      //prepare start date
      java.util.Calendar calendar_start = java.util.Calendar.getInstance();
      Date date_start = new Date(input_start_date);
      calendar_start.setTime(date_start);

      //prepare end date
      java.util.Calendar calendar_end = java.util.Calendar.getInstance();
      Date date_end = new Date(input_end_date);
      calendar_end.setTime(date_end);

      //projection of DB columns
      String[] l_projection = new String[]{"title", "dtstart", "dtend", "eventLocation", "allDay"};

      //actual query
      Cursor cursor = contentResolver.query(l_eventUri, l_projection, "( dtstart >" + calendar_start.getTimeInMillis() + " AND dtend <" + calendar_end.getTimeInMillis() + ")", null, "dtstart ASC");

      int i = 0;
      while (cursor.moveToNext()) {
        result.put(i++, new JSONObject().put("title", cursor.getString(0)).put("dtstart", cursor.getLong(1)).put("dtend", cursor.getLong(2)).put("eventLocation", cursor.getString(3) != null ? cursor.getString(3) : "").put("allDay", cursor.getInt(4)));
      }
      callback.success("" + result);
      return true;
    } catch (JSONException e) {
      System.err.println("Exception: " + e.getMessage());
    }
    return false;
  }

  public Integer getEventCountInRange(JSONArray args) throws JSONException{
    Integer eventsCount = 0;
      try {
          Uri l_eventUri;
          if (Build.VERSION.SDK_INT >= 8) {
            l_eventUri = Uri.parse("content://com.android.calendar/events");
          } else {
            l_eventUri = Uri.parse("content://calendar/events");
          }
          ContentResolver contentResolver = this.cordova.getActivity().getContentResolver();
          JSONObject jsonFilter = args.getJSONObject(0);
          JSONArray result = new JSONArray();
          long input_start_date = jsonFilter.optLong("startTime");
          long input_end_date = jsonFilter.optLong("endTime");

          //prepare start date
          java.util.Calendar calendar_start = java.util.Calendar.getInstance();
          Date date_start = new Date(input_start_date);
          calendar_start.setTime(date_start);
          calendar_start.add(calendar_start.DATE, -1);

          //prepare end date
          java.util.Calendar calendar_end = java.util.Calendar.getInstance();
          Date date_end = new Date(input_end_date);
          calendar_end.setTime(date_end);

          //projection of DB columns
          String[] l_projection = new String[]{"title", "dtstart", "dtend", "eventLocation", "allDay"};

          //actual query
          Cursor cursor = contentResolver.query(l_eventUri, l_projection, "( dtstart >" + calendar_start.getTimeInMillis() + " AND dtend <" + calendar_end.getTimeInMillis() + ")", null, "dtstart ASC");
          
          int i = 0;
          while (cursor.moveToNext()) {
              result.put(i++, new JSONObject().put("title", cursor.getString(0)).put("dtstart", cursor.getLong(1)).put("dtend", cursor.getLong(2)).put("eventLocation", cursor.getString(3) != null ? cursor.getString(3) : "").put("allDay", cursor.getInt(4)));
            }
          
          if(result!=null){
          eventsCount = result.length();
          }
          
        } catch (JSONException e) {
          System.err.println("Exception: " + e.getMessage());
        }

    return eventsCount;
  }
  
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    Integer currentNumberOfEvents = 0;
    try {
    currentNumberOfEvents = getEventCountInRange(eventRequestData);
    if (requestCode == RESULT_CODE_CREATE) {
        if (currentNumberOfEvents.compareTo(existingNumberOfEvents) > 0) {
          callback.success();
        }else{
          callback.error("Unable to add event");
        }
      } else {
        callback.error("Unable to add event");
      }
  } catch (JSONException e) {
    // TODO Auto-generated catch block
    e.printStackTrace();
  }

  }
}
