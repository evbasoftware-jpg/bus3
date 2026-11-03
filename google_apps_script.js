/**
 * =========================================================================
 * GOOGLE APPS SCRIPT FOR LIVE BUS TRACKING SYSTEM
 * =========================================================================
 *
 * শীটগুলোর নাম ও গঠন:
 * 1) "Buses" শীটের কলম (মোট ১২টি):
 *    State | District | Bus_Number | Bus_Name | Category | Days | Source | Dep_Time | Destination | Arr_Time | Selected_Stops | Status
 *
 * 2) "Master_Stops" শীটের কলম (৩টি):
 *    Stop_Name | Latitude | Longitude
 *    (ম্যাপে ক্লিক করে বা অ্যাপ থেকে নতুন স্থান/স্টপ যোগ করলে এই শীটে সরাসরি সেভ হবে)
 *
 * 3) "BusStops" শীটের কলম:
 *    Stop_Name | Latitude | Longitude (অথবা বাসের স্টপ ভিত্তিক সময়সূচি)
 *
 * 4) "LiveGPS" শীটের কলম (৬টি):
 *    Bus_Number | Latitude | Longitude | Speed | Accuracy | Last_Update
 *
 * 5) "BusClosures" শীটের কলম:
 *    Date | Bus_Number | Bus_Name | Status_Type | Trip_Time | Day_Or_Date | Reason | Updated_At
 * =========================================================================
 */

function doGet(e) {
  var action = (e && e.parameter) ? e.parameter.action : "";

  if (action === "getBuses") {
    return handleGetBuses();
  } else if (action === "getStopsList") {
    return handleGetStopsList();
  } else if (action === "getLive") {
    return handleGetLive(e.parameter.busNo);
  } else if (action === "addMasterStop") {
    return handleAddMasterStop(e.parameter);
  } else if (action === "addStop") {
    return handleAddStop(e.parameter);
  }

  return createJsonResponse({ ok: false, error: "Invalid GET action" });
}

function doPost(e) {
  var data = {};
  if (e && e.postData && e.postData.contents) {
    try {
      data = JSON.parse(e.postData.contents);
    } catch (err) {
      // urlencoded fallback
      data = e.parameter || {};
    }
  } else if (e && e.parameter) {
    data = e.parameter;
  }

  var action = data.action;

  if (action === "addBus") {
    return handleAddBus(data);
  } else if (action === "addStop") {
    return handleAddStop(data);
  } else if (action === "addMasterStop") {
    return handleAddMasterStop(data);
  } else if (action === "updateGPS") {
    return handleUpdateGPS(data);
  } else if (action === "updateBusClosedStatus") {
    return handleUpdateBusClosedStatus(data);
  }

  return createJsonResponse({ ok: false, error: "Unknown POST action: " + action });
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

    // Status কলমে 'active' লেখা থাকলে তবেই বাসের তথ্য দেখানো হবে
    if (r[11] !== undefined && status !== "active") {
      continue;
    }

    var selectedStopsRaw = r[10] || "";
    var selectedStops = [];
    if (typeof selectedStopsRaw === "string") {
      selectedStops = selectedStopsRaw.split(",").map(function(s) { 
        return s.replace(/[\u200B-\u200D\uFEFF]/g, "").trim().toUpperCase(); 
      }).filter(function(s) { return s.length > 0; });
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
// ২. সমস্ত মাস্টার স্টপ তালিকা আনা ("Master_Stops" বা "BusStops" থেকে)
// -------------------------------------------------------------
function handleGetStopsList() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName("Master_Stops") || ss.getSheetByName("BusStops");
  if (!sheet) return createJsonResponse({ ok: true, stops: [] });

  var rows = sheet.getDataRange().getValues();
  var stops = [];
  for (var i = 1; i < rows.length; i++) {
    var name = String(rows[i][0] || "").replace(/[\u200B-\u200D\uFEFF]/g, "").trim().toUpperCase();
    var lat = Number(rows[i][1]);
    var lon = Number(rows[i][2]);
    if (name && !isNaN(lat) && !isNaN(lon) && lat !== 0) {
      stops.push({ name: name, stopName: name, lat: lat, lon: lon, latitude: lat, longitude: lon });
    }
  }

  return createJsonResponse({ ok: true, stops: stops });
}

// -------------------------------------------------------------
// ৩. মাস্টার স্টপ সরাসরি "Master_Stops" শিটে যুক্ত বা আপডেট করা
// -------------------------------------------------------------
function handleAddMasterStop(data) {
  var stopName = String(data.stopName || data.name || "").replace(/[\u200B-\u200D\uFEFF]/g, "").trim().toUpperCase();
  var lat = Number(data.lat || data.latitude);
  var lon = Number(data.lon || data.longitude);

  if (!stopName || isNaN(lat) || isNaN(lon) || lat === 0 || lon === 0) {
    return createJsonResponse({ ok: false, error: "Valid stopName, lat, and lon are required" });
  }

  var ss = SpreadsheetApp.getActiveSpreadsheet();
  // Master_Stops শিট অগ্রাধিকার পাবে
  var sheet = ss.getSheetByName("Master_Stops");
  if (!sheet) {
    sheet = ss.getSheetByName("BusStops");
  }
  if (!sheet) {
    sheet = ss.insertSheet("Master_Stops");
    sheet.appendRow(["Stop_Name", "Latitude", "Longitude"]);
  }

  var rows = sheet.getDataRange().getValues();
  var existingRowIndex = -1;

  for (var i = 1; i < rows.length; i++) {
    var sName = String(rows[i][0] || "").replace(/[\u200B-\u200D\uFEFF]/g, "").trim().toUpperCase();
    if (sName === stopName) {
      existingRowIndex = i + 1; // 1-based index
      break;
    }
  }

  if (existingRowIndex !== -1) {
    // স্টপটি আগে থেকে থাকলে অক্ষাংশ-দ্রাঘিমাংশ আপডেট করা
    sheet.getRange(existingRowIndex, 2, 1, 2).setValues([[lat, lon]]);
    return createJsonResponse({
      ok: true,
      updated: true,
      message: "Stop coordinates updated in Master_Stops",
      stopName: stopName,
      lat: lat,
      lon: lon
    });
  } else {
    // নতুন স্টপ Master_Stops-এর নতুন সারিতে যোগ করা
    sheet.appendRow([stopName, lat, lon]);
    return createJsonResponse({
      ok: true,
      added: true,
      message: "New stop added to Master_Stops successfully",
      stopName: stopName,
      lat: lat,
      lon: lon
    });
  }
}

// -------------------------------------------------------------
// ৪. বাসের লাইভ জিপিএস লোকেশন আনা
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
          selectedStops = rawStops.split(",").map(function(s) { 
            return s.replace(/[\u200B-\u200D\uFEFF]/g, "").trim().toUpperCase(); 
          }).filter(Boolean);
        }
        break;
      }
    }
  }

  // বাসের স্টপগুলোর কো-অর্ডিনেট সহ তালিকা আনা
  var stopsWithCoords = [];
  var stopsSheet = ss.getSheetByName("Master_Stops") || ss.getSheetByName("BusStops");
  if (stopsSheet && selectedStops.length > 0) {
    var sRows = stopsSheet.getDataRange().getValues();
    var stopsMap = {};
    for (var k = 1; k < sRows.length; k++) {
      var sName = String(sRows[k][0] || "").replace(/[\u200B-\u200D\uFEFF]/g, "").trim().toUpperCase();
      var sLat = Number(sRows[k][1]);
      var sLon = Number(sRows[k][2]);
      if (sName && !isNaN(sLat) && !isNaN(sLon) && sLat !== 0) {
        stopsMap[sName] = { lat: sLat, lon: sLon };
      }
    }
    for (var m = 0; m < selectedStops.length; m++) {
      var sItem = selectedStops[m];
      if (stopsMap[sItem]) {
        stopsWithCoords.push({
          name: sItem,
          stopName: sItem,
          lat: stopsMap[sItem].lat,
          latitude: stopsMap[sItem].lat,
          lon: stopsMap[sItem].lon,
          longitude: stopsMap[sItem].lon
        });
      }
    }
  }

  return createJsonResponse({
    ok: true,
    live: liveData,
    selectedStops: selectedStops,
    stops: stopsWithCoords
  });
}

// -------------------------------------------------------------
// ৫. নতুন বাসের তথ্য সংরক্ষণ (Status স্বয়ংক্রিয়ভাবে 'active' যুক্ত হবে)
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
// ৬. স্টপ যুক্ত করা (Buses শিটের Selected_Stops এবং Master_Stops উভয় স্থানে)
// -------------------------------------------------------------
function handleAddStop(data) {
  var busNo = String(data.busNumber || "").trim().toUpperCase();
  var stopName = String(data.stopName || "").trim().toUpperCase();

  if (!stopName) {
    return createJsonResponse({ ok: false, error: "stopName is required" });
  }

  // যদি বাস নম্বর না থাকে, এটি সরাসরি Master_Stops এ যুক্ত হবে
  if (!busNo) {
    return handleAddMasterStop(data);
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
        sheet.getRange(i + 1, 11).setValue(updatedStopsStr);
        updated = true;
      }
      break;
    }
  }

  // কো-অর্ডিনেট থাকলে Master_Stops-এও সংরক্ষণ করুন যাতে অন্যান্য বাসও এই স্টপ পায়
  if (data.lat && data.lon) {
    handleAddMasterStop(data);
  }

  return createJsonResponse({ ok: true, updated: updated, message: "Stop added to Selected_Stops and Master_Stops" });
}

// -------------------------------------------------------------
// ৭. জিপিএস লোকেশন আপডেট করা (LiveGPS শিট)
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
// ৮. বাস বন্ধ বা বাতিল স্ট্যাটাস আপডেট (BusClosures শিট)
// -------------------------------------------------------------
function handleUpdateBusClosedStatus(data) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName("BusClosures");
  if (!sheet) {
    sheet = ss.insertSheet("BusClosures");
    sheet.appendRow(["Date", "Bus_Number", "Bus_Name", "Status_Type", "Trip_Time", "Day_Or_Date", "Reason", "Updated_At"]);
  }

  var updatedAt = Utilities.formatDate(new Date(), "Asia/Kolkata", "dd/MM/yyyy, hh:mm:ss a");
  sheet.appendRow([
    data.date || "",
    data.busNumber || "",
    data.busName || "",
    data.statusType || "",
    data.tripTime || "",
    data.dayOrDate || "",
    data.reason || "",
    updatedAt
  ]);

  return createJsonResponse({ ok: true, message: "Bus closure status recorded" });
}

// -------------------------------------------------------------
// হেল্পার: JSON রেসপন্স রিটার্ন ফাংশন
// -------------------------------------------------------------
function createJsonResponse(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
