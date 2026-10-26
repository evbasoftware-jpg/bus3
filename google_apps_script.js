/**
 * =========================================================================
 * GOOGLE APPS SCRIPT FOR LIVE BUS TRACKING SYSTEM
 * =========================================================================
 *
 * শীটগুলোর নাম ও গঠন:
 * 1) "Buses" শীটের কলম (মোট ১২টি):
 *    State | District | Bus_Number | Bus_Name | Category | Days | Source | Dep_Time | Destination | Arr_Time | Selected_Stops | Status
 *
 *    - নতুন বাস যুক্ত হলে 'Status' কলমে স্বয়ংক্রিয়ভাবে 'active' বসবে।
 *    - কোনো বাসের 'active' লেখাটি মুছে দিলে (খালি রাখলে) সেই বাস সার্চ লিস্ট বা ক্যাটালগে দেখাবে না।
 *
 * 2) "BusStops" শীটের কলম:
 *    Stop_Name | Latitude | Longitude
 *    (ব্যবহারকারীর ২ নম্বর নির্দেশনা অনুযায়ী বাসে নতুন স্টপ যুক্ত করার সময় এই শীটে কোনো ডাটা ঢুকবে না, কেবল Buses শীটের Selected_Stops আপডেট হবে)
 *
 * 3) "LiveGPS" শীটের কলম:
 *    Bus_Number | Latitude | Longitude | Speed | Accuracy | Last_Update
 * =========================================================================
 */

function doGet(e) {
  var action = e.parameter.action;

  if (action === "getBuses") {
    return handleGetBuses();
  } else if (action === "getStopsList") {
    return handleGetStopsList();
  } else if (action === "getLive") {
    return handleGetLive(e.parameter.busNo);
  }

  return createJsonResponse({ ok: false, error: "Invalid action" });
}

function doPost(e) {
  var data;
  try {
    data = JSON.parse(e.postData.contents);
  } catch (err) {
    return createJsonResponse({ ok: false, error: "Invalid JSON body" });
  }

  var action = data.action;

  if (action === "addBus") {
    return handleAddBus(data);
  } else if (action === "addStop") {
    return handleAddStop(data);
  } else if (action === "updateGPS") {
    return handleUpdateGPS(data);
  }

  return createJsonResponse({ ok: false, error: "Unknown POST action" });
}

// -------------------------------------------------------------
// ১. বাস তালিকা ও সার্চ রেজাল্ট (শুধুমাত্র active বাসগুলো দেখাবে)
// -------------------------------------------------------------
function handleGetBuses() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName("Buses");
  if (!sheet) return createJsonResponse({ ok: true, buses: [] });

  var rows = sheet.getDataRange().getValues();
  if (rows.length <= 1) return createJsonResponse({ ok: true, buses: [] });

  var buses = [];

  for (var i = 1; i < rows.length; i++) {
    var r = rows[i];
    var busNumber = String(r[2] || "").trim();
    if (!busNumber) continue;

    // ১২ নম্বর কলম: Status (ইনডেক্স 11)
    var status = String(r[11] || "").trim().toLowerCase();

    // ব্যবহারকারীর ৩ নম্বর নির্দেশনা:
    // Status কলমে 'active' লেখা থাকলে তবেই বাসের তথ্য দেখানো হবে।
    // যদি 'active' ডিলিট করে দেওয়া হয় (খালি থাকে বা অন্য কিছু থাকে), তাহলে লিস্টে আসবে না।
    if (r[11] !== undefined && status !== "active") {
      continue;
    }

    var selectedStopsRaw = r[10] || "";
    var selectedStops = [];
    if (typeof selectedStopsRaw === "string") {
      selectedStops = selectedStopsRaw.split(",").map(function(s) { return s.trim().toUpperCase(); }).filter(function(s) { return s.length > 0; });
    } else if (Array.isArray(selectedStopsRaw)) {
      selectedStops = selectedStopsRaw;
    }

    buses.push({
      id: i,
      state: r[0] || "",
      district: r[1] || "",
      number: busNumber,
      name: r[3] || "",
      category: r[4] || "PUBLIC",
      days: r[5] || "Daily",
      source: String(r[6] || "").trim().toUpperCase(),
      depTime: String(r[7] || ""),
      dest: String(r[8] || "").trim().toUpperCase(),
      arrTime: String(r[9] || ""),
      selectedStops: selectedStops,
      status: r[11] || "active"
    });
  }

  return createJsonResponse({ ok: true, buses: buses });
}

// -------------------------------------------------------------
// ২. সমস্ত মাস্টার স্টপ তালিকা আনা
// -------------------------------------------------------------
function handleGetStopsList() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName("BusStops");
  if (!sheet) return createJsonResponse({ ok: true, stops: [] });

  var rows = sheet.getDataRange().getValues();
  var stops = [];
  for (var i = 1; i < rows.length; i++) {
    var name = String(rows[i][0] || "").trim().toUpperCase();
    var lat = Number(rows[i][1]);
    var lon = Number(rows[i][2]);
    if (name && !isNaN(lat) && !isNaN(lon)) {
      stops.push({ name: name, stopName: name, lat: lat, lon: lon, latitude: lat, longitude: lon });
    }
  }

  return createJsonResponse({ ok: true, stops: stops });
}

// -------------------------------------------------------------
// ৩. বাসের লাইভ জিপিএস লোকেশন আনা
// -------------------------------------------------------------
function handleGetLive(busNo) {
  if (!busNo) return createJsonResponse({ ok: false, error: "busNo required" });
  busNo = String(busNo).trim().toUpperCase();

  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var gpsSheet = ss.getSheetByName("LiveGPS");
  var liveData = null;

  if (gpsSheet) {
    var rows = gpsSheet.getDataRange().getValues();
    for (var i = 1; i < rows.length; i++) {
      var num = String(rows[i][0] || "").trim().toUpperCase();
      if (num === busNo) {
        liveData = {
          lat: Number(rows[i][1]),
          lon: Number(rows[i][2]),
          speed: Number(rows[i][3] || 0),
          accuracy: Number(rows[i][4] || 10),
          lastUpdate: String(rows[i][5] || "")
        };
        break;
      }
    }
  }

  // বাসের সিলেক্টেড স্টপ তালিকা আনা
  var selectedStops = [];
  var busesSheet = ss.getSheetByName("Buses");
  if (busesSheet) {
    var bRows = busesSheet.getDataRange().getValues();
    for (var j = 1; j < bRows.length; j++) {
      if (String(bRows[j][2] || "").trim().toUpperCase() === busNo) {
        var rawStops = bRows[j][10] || "";
        if (typeof rawStops === "string") {
          selectedStops = rawStops.split(",").map(function(s) { return s.trim().toUpperCase(); }).filter(Boolean);
        }
        break;
      }
    }
  }

  return createJsonResponse({
    ok: true,
    live: liveData,
    selectedStops: selectedStops,
    stops: []
  });
}

// -------------------------------------------------------------
// ৪. নতুন বাসের তথ্য সংরক্ষণ (Status স্বয়ংক্রিয়ভাবে 'active' যুক্ত হবে)
// -------------------------------------------------------------
function handleAddBus(data) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName("Buses");
  if (!sheet) {
    sheet = ss.insertSheet("Buses");
    sheet.appendRow([
      "State", "District", "Bus_Number", "Bus_Name", "Category",
      "Days", "Source", "Dep_Time", "Destination", "Arr_Time",
      "Selected_Stops", "Status"
    ]);
  }

  var selectedStopsStr = "";
  if (Array.isArray(data.selectedStops)) {
    selectedStopsStr = data.selectedStops.join(",");
  } else if (data.selectedStops) {
    selectedStopsStr = String(data.selectedStops);
  }

  // ৩ নম্বর নির্দেশনা: নতুন বাসের ক্ষেত্রে Status কলমে "active" লেখা বসবে
  var statusValue = data.status || "active";

  sheet.appendRow([
    data.state || "West Bengal",
    data.district || "Birbhum",
    data.busNumber || "",
    data.busName || "",
    data.category || "PUBLIC",
    data.days || "Daily",
    String(data.source || "").toUpperCase(),
    data.depTime || "",
    String(data.dest || "").toUpperCase(),
    data.arrTime || "",
    selectedStopsStr,
    statusValue
  ]);

  return createJsonResponse({ ok: true, message: "Bus added successfully with active status" });
}

// -------------------------------------------------------------
// ৫. স্টপ যুক্ত করা (ব্যবহারকারীর ২ নম্বর নির্দেশনা: BusStops শীটে যুক্ত হবে না, কেবল Buses শীটের Selected_Stops এ যুক্ত হবে)
// -------------------------------------------------------------
function handleAddStop(data) {
  var busNo = String(data.busNumber || "").trim().toUpperCase();
  var stopName = String(data.stopName || "").trim().toUpperCase();

  if (!busNo || !stopName) {
    return createJsonResponse({ ok: false, error: "busNumber and stopName are required" });
  }

  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName("Buses");
  if (!sheet) return createJsonResponse({ ok: false, error: "Buses sheet not found" });

  var rows = sheet.getDataRange().getValues();
  var updated = false;

  for (var i = 1; i < rows.length; i++) {
    var currentBusNo = String(rows[i][2] || "").trim().toUpperCase();
    if (currentBusNo === busNo) {
      var currentStopsRaw = String(rows[i][10] || "").trim();
      var stopsArray = currentStopsRaw ? currentStopsRaw.split(",").map(function(s) { return s.trim().toUpperCase(); }) : [];

      if (stopsArray.indexOf(stopName) === -1) {
        stopsArray.push(stopName);
        var updatedStopsStr = stopsArray.join(",");
        // 11th column is Selected_Stops (row index i+1, column index 11)
        sheet.getRange(i + 1, 11).setValue(updatedStopsStr);
        updated = true;
      }
      break;
    }
  }

  // ২ নম্বর নির্দেশনা অনুযায়ী "BusStops" শীটে নতুন নাম যুক্ত করার অংশ সম্পূর্ণ বাদ দেওয়া হয়েছে।

  return createJsonResponse({ ok: true, updated: updated, message: "Stop added to Selected_Stops in Buses sheet only" });
}

// -------------------------------------------------------------
// ৬. জিপিএস লোকেশন আপডেট করা (LiveGPS শীট)
// -------------------------------------------------------------
function handleUpdateGPS(data) {
  var busNo = String(data.busNumber || "").trim().toUpperCase();
  if (!busNo) return createJsonResponse({ ok: false, error: "busNumber required" });

  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName("LiveGPS");
  if (!sheet) {
    sheet = ss.insertSheet("LiveGPS");
    sheet.appendRow(["Bus_Number", "Latitude", "Longitude", "Speed", "Accuracy", "Last_Update"]);
  }

  var lat = Number(data.lat);
  var lon = Number(data.lon);
  var speed = Number(data.speed || 0);
  var accuracy = Number(data.accuracy || 10);
  var timeStr = Utilities.formatDate(new Date(), "Asia/Kolkata", "dd/MM/yyyy, hh:mm:ss a");

  var rows = sheet.getDataRange().getValues();
  var found = false;

  for (var i = 1; i < rows.length; i++) {
    if (String(rows[i][0] || "").trim().toUpperCase() === busNo) {
      sheet.getRange(i + 1, 2, 1, 5).setValues([[lat, lon, speed, accuracy, timeStr]]);
      found = true;
      break;
    }
  }

  if (!found) {
    sheet.appendRow([busNo, lat, lon, speed, accuracy, timeStr]);
  }

  return createJsonResponse({ ok: true, message: "GPS updated" });
}

// -------------------------------------------------------------
// হেল্পার: JSON রেসপন্স রিটার্ন ফাংশন
// -------------------------------------------------------------
function createJsonResponse(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
