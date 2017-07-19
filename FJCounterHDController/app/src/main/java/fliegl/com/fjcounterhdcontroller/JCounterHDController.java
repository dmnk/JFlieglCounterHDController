package fliegl.com.fjcounterhdcontroller;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

/**
 * Created by Johannes Dürr on 06.07.17.
 */

public class JCounterHDController {

    // the partnering activity
    AppCompatActivity activity;

    // Bluetooth LE ingrediants
    final static public UUID flieglCounterUUID = UUID.fromString("C93AAAA0-C497-4C95-8699-01B142AF0C24");
    final static public UUID flieglCounterServiceUUID = UUID.fromString("C93ABBB0-C497-4C95-8699-01B142AF0C24");
    private static final UUID CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String TAG = "CounterHDController: ";
    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;
    private BluetoothAdapter mBluetoothAdapter;
    private int REQUEST_ENABLE_BT = 1;
    private Handler mHandler;
    private static final long SCAN_PERIOD = 2000;
    private BluetoothLeScanner mLEScanner;
    private ScanSettings settings;
    private List<ScanFilter> filterlist = new ArrayList<>();
    private List<BluetoothDevice> peripheralList = new ArrayList<>();
    private Timer characteristicRetrievalTimer = null;
    private List<BluetoothGattCharacteristic> characteristicList = new ArrayList<>();
    private List<BluetoothGattCharacteristic> characteristicDoneList = new ArrayList<>();


    // GATT client
    BluetoothGatt mGatt = null;
    private BluetoothGattCallback mGattCallback = null;


    // Listener pattern
    private CounterHDControllerListener listener;



    // Constructor

    public JCounterHDController(final AppCompatActivity activity)
    {
        this.activity = activity;
        this.listener = null;
        characteristicRetrievalTimer = new Timer();

        // Make sure, we have propper permissions to use bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android M Permission check 
            if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                builder.setTitle("This app needs location access");
                builder.setMessage("Please grant location access so this app can detect beacons.");
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {

                    public void onDismiss(DialogInterface dialog) {
                        activity.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
                    }
                });
                builder.show();
            }
            mHandler = new Handler();

            if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                Toast.makeText(activity, "BLE Not Supported",
                        Toast.LENGTH_SHORT).show();
                activity.finish();
            }
            final BluetoothManager bluetoothManager =
                    (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = bluetoothManager.getAdapter();
        }

        // Make sure we have propper scan filter and scanner in place to only find counterhd peripherals
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(flieglCounterUUID.toString())).build();
        filterlist.add(filter);
        mLEScanner = mBluetoothAdapter.getBluetoothLeScanner();
        settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

    }

    public void setCounterHDControllerListener(CounterHDControllerListener listener) {
        this.listener = listener;
    }


    public void startScanning(Boolean enable)
    {
        if (enable) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mLEScanner.stopScan(mScanCallback);
                }
            }, SCAN_PERIOD);
            mLEScanner.startScan(filterlist, settings, mScanCallback);
        } else {
            mLEScanner.stopScan(mScanCallback);
        }
    }

    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {

            BluetoothDevice btDevice = result.getDevice();

            List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();
            if(uuids != null)
            {
                if(!uuids.isEmpty())
                {
                    for(ParcelUuid uid : uuids)
                    {
                        if (uid.getUuid().toString().equalsIgnoreCase(flieglCounterUUID.toString()))
                        {
                            if (peripheralList.size() > 0) {
                                for (BluetoothDevice d : peripheralList) {
                                    if (d.getAddress().equalsIgnoreCase(btDevice.getAddress())) {
                                        Log.i(TAG, "Found: DUPLICATE " + btDevice.getName() + " (" + btDevice.getAddress() + ") \n" + uid.getUuid().toString() + String.format("\n%d Peripherals", peripheralList.size()));
                                    } else {
                                        peripheralList.add(btDevice);
                                        Log.i(TAG, "Found: " + btDevice.getName() + " (" + btDevice.getAddress() + ") \n" + uid.getUuid().toString() + String.format("\n%d Peripherals", peripheralList.size()));
                                    }
                                }
                            }else{
                                peripheralList.add(btDevice);
                                Log.i(TAG, "Found: " + btDevice.getName() + " (" + btDevice.getAddress() + ") \n" + uid.getUuid().toString() + String.format("\n%d Peripherals", peripheralList.size()));
                            }
                        }
                    }
                }
            }
            // tell listeners
            if (listener != null){
                listener.cc_didFindPeripherals(peripheralList);
            }
        }
    };

    protected void readCharacteristicList(BluetoothGatt gatt)
    {
        if(characteristicDoneList.size()>0){
            characteristicList.removeAll(characteristicDoneList);
        }

        if (characteristicList.size() > 0){
            BluetoothGattCharacteristic c = characteristicList.get(0);
            if (isCharacterisitcReadable(c))
            {
                gatt.readCharacteristic(c);
            }
        }else {
            characteristicDoneList.removeAll(characteristicDoneList);
            characteristicList.removeAll(characteristicList);
        }


    }

    // Connection handling etc.
    public void disconnectPeripheral(){

        if (mGatt != null){
            mGatt.disconnect();
            mGatt.close();
            mGatt = null;
        }
        peripheralList.clear();

    }

    public void connectPeripheral(BluetoothDevice d){

        mGattCallback = new BluetoothGattCallback() {

            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (listener != null){
                    listener.cc_didChangeConnectionState(gatt, status, newState);



                    switch (newState){
                        case 0:
                            // Disconnected
                            Log.i(TAG, "mGatCallback: Disconnected");
                            break;
                        case 1:
                            // Connecteing
                            Log.i(TAG, "mGatCallback: Connecting");
                            break;
                        case 2:
                            // Connected
                            Log.i(TAG, "mGatCallback: Connected");
                            gatt.discoverServices();
                            break;

                        case 3:
                            // Disconnecting
                            Log.i(TAG, "mGatCallback: Disconnecting");
                            break;

                        default:
                            break;

                    }
                }
            }

            @Override
            // New services discovered
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                Log.i(TAG, "Discovered a new Service....");
                mGatt = gatt;
                for (BluetoothGattService s : gatt.getServices()
                     ) {
                    // only counter service...
                    if (s.getUuid().toString().equalsIgnoreCase(flieglCounterServiceUUID.toString())) {
                        // add to characteristic list if not allready in there...
                        for (BluetoothGattCharacteristic c : s.getCharacteristics()
                                ) {
                            if (!characteristicList.contains(c)) {
                                characteristicList.add(c);
                                Log.i(TAG, String.format("Added characteristic to list: %s from Service: %s", c.getUuid().toString(), c.getService().getUuid().toString()));
                            }
                        }
                        TimerTask charTimeTask = new TimerTask() {
                            @Override
                            public void run() {
                            if (mGatt != null)
                                readCharacteristicList(mGatt);
                            }
                        };
                        characteristicRetrievalTimer.schedule(charTimeTask, 0, 500);
                    }
                }

            }

            @Override
            // Result of a characteristic read operation
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // check if notifiable
                    if (isCharacterisiticNotifiable(characteristic)){
                        // set notify
                        gatt.setCharacteristicNotification(characteristic, true);
                        // and write descriptor
                        BluetoothGattDescriptor desc = characteristic.getDescriptor(CONFIG_DESCRIPTOR);
                        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(desc);
                        Log.i(TAG, String.format("Enabling notification for: %s", characteristic.getUuid().toString()));
                    }

                    for (BluetoothGattCharacteristic tChar : characteristicList
                         ) {
                        if (tChar.getUuid().toString().equalsIgnoreCase(characteristic.getUuid().toString())){
                            characteristicDoneList.add(tChar);
                        }
                    }

                    if (listener != null){
                        listener.cc_didReadCharacteristicValue(gatt, characteristic);
                    }
                    // process value
                    processCharacteristicValue(gatt, characteristic);
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                Log.i(TAG, String.format("Characteristic changed: %s", characteristic.getUuid().toString()));
                if (listener != null)
                {
                    listener.cc_didChangeCharacteristicValue(gatt, characteristic);
                }
                // process value
                processCharacteristicValue(gatt, characteristic);
            }
        };

        d.connectGatt(activity, false, mGattCallback);
    }

    // Updated or read characteristic values are processed here depending on wich UUID they state to have:
    private void processCharacteristicValue(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic){

        String uuidString = characteristic.getUuid().toString().toUpperCase();
        switch (uuidString){

            // Param Transport (reading back predefined parameters)
            case "C93ABBC9-C497-4C95-8699-01B142AF0C24":

                break;

            // Beacon Basic Info
            case "C93ABBB1-C497-4C95-8699-01B142AF0C24":
                Integer major;
                Integer minor;
                Integer txPower;
                String name = "Unknown Name";
                // minor
                minor = getIntFromBytes(characteristic.getValue(), 0,2);
                // major
                major = getIntFromBytes(characteristic.getValue(), 2, 2);
                // tx power
                txPower = getIntFromBytes(characteristic.getValue(), 15, 1);
                // local name
                try {
                    name = new String(characteristic.getValue(), 4,11, "UTF8");
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                if (listener != null) {
                    listener.cc_didUpdateBluetoothLocalName(name);
                    listener.cc_didUpdateMajorValue(major);
                    listener.cc_didUpdateMinorValue(minor);
                    listener.cc_didUpdateTXPower(txPower);
                }
                break;

            // UUID
            case "C93ABBB3-C497-4C95-8699-01B142AF0C24":

                String beaconUUID = "UUID unavailable";
                UUID cUUID = UUID.nameUUIDFromBytes(Arrays.copyOfRange(characteristic.getValue(), 0, 16));
                beaconUUID = cUUID.toString();
                if (listener != null){
                    listener.cc_didUpdateUUID(beaconUUID);
                }
                break;

            // Battery information
            case "C93ABBC0-C497-4C95-8699-01B142AF0C24":
                Integer charge = 0;
                Boolean isDCDCEnabled = false;
                charge = getIntFromBytes(characteristic.getValue(), 0, 1);
                if (characteristic.getValue()[1] == 0x01)
                {
                    isDCDCEnabled = true;
                }
                if (listener != null){
                    listener.cc_didUpdateBatteryInfo(charge, isDCDCEnabled);
                }
                break;

            // button state
            case "C93ABBB7-C497-4C95-8699-01B142AF0C24":
                byte testByte = (byte) characteristic.getValue()[0];
                if (testByte != 0){
                    Log.i(TAG, String.format("TESTBYTE: %d", testByte));
                }
                if(listener != null) {
                    if ((testByte & 1) == 1) {
                        // Button 3
                        listener.cc_didUpdateButton_3_state(true);
                    }else{
                        listener.cc_didUpdateButton_3_state(false);
                    }
                    if ((testByte & 2) == 2) {
                        // Button 1
                        listener.cc_didUpdateButton_1_state(true);
                    }else{
                        listener.cc_didUpdateButton_1_state(false);
                    }
                    if ((testByte & 4) == 4) {
                        // Button 2
                        listener.cc_didUpdateButton_2_state(true);
                    }else{
                        listener.cc_didUpdateButton_2_state(false);
                    }
                    if ((testByte & 8) == 8) {
                        // Button 4
                        listener.cc_didUpdateButton_4_state(true);
                    }else{
                        listener.cc_didUpdateButton_4_state(false);
                    }
                }

                break;

            // Event Totals
            case "C93ABBC3-C497-4C95-8699-01B142AF0C24":

                Integer xEventCount = getIntFromBytes(characteristic.getValue(),0,2);
                Integer yEventCount = getIntFromBytes(characteristic.getValue(),2,2);
                Integer zEventCount = getIntFromBytes(characteristic.getValue(),4,2);
                Integer xActiveTime = getIntFromBytes(characteristic.getValue(),6,2);
                Integer yActiveTime = getIntFromBytes(characteristic.getValue(),8,2);
                Integer zActiveTime = getIntFromBytes(characteristic.getValue(),10,2);
                Integer xProcessCount = getIntFromBytes(characteristic.getValue(),12,2);
                Integer yProcessCount = getIntFromBytes(characteristic.getValue(),14,2);
                Integer zProcessCount = getIntFromBytes(characteristic.getValue(),16,2);
                if (listener!= null){
                    listener.cc_didUpdateEventTotals(xEventCount,xActiveTime, xProcessCount, yEventCount, yActiveTime, yProcessCount, zEventCount, zActiveTime, zProcessCount);
                }
                break;

            // Device State
            case "C93ABBFF-C497-4C95-8699-01B142AF0C24":

                Integer deviceType = getIntFromBytes(characteristic.getValue(),0,2);
                Integer deviceRevision = getIntFromBytes(characteristic.getValue(),2,2);
                Integer buildNumber = getIntFromBytes(characteristic.getValue(), 4, 2);
                Integer rs_count = getIntFromBytes(characteristic.getValue(), 6, 2);

                Integer firmwareMajor = getIntFromBytes(characteristic.getValue(), 8, 1);
                Integer firmwareMinor = getIntFromBytes(characteristic.getValue(), 9, 1);;

                Integer statusBits = getIntFromBytes(characteristic.getValue(), 10, 4);
                Integer rs_time = getIntFromBytes(characteristic.getValue(), 14, 4);
                Integer user_role = getIntFromBytes(characteristic.getValue(), 18, 1);

                if (listener != null)
                {
                    listener.cc_didUpdateDeviceType(deviceType);
                    listener.cc_didUpdateDeviceRevision(deviceRevision);
                    listener.cc_didUpdateBuildNumber(buildNumber);
                    listener.cc_didUpdateRS_Count(rs_count);
                    listener.cc_didUpdateRS_Time(rs_time);
                    listener.cc_didUpdateFirmwareVersion(firmwareMajor, firmwareMinor);
                    listener.cc_didUpdateUserRole(user_role);
                    listener.cc_didUpdateStatusFlags(statusBits);
                }
                break;

            // Lis3dh incl Characteristic
            case "C93ABBB8-C497-4C95-8699-01B142AF0C24":
                Integer xInclination = getIntFromBytes(characteristic.getValue(), 0, 2);
                Integer yInclination = getIntFromBytes(characteristic.getValue(), 2, 2);
                Integer zInclination = getIntFromBytes(characteristic.getValue(), 4, 2);
                Integer xCorrectedInclination = getIntFromBytes(characteristic.getValue(), 6, 2);
                Integer yCorrectedInclination = getIntFromBytes(characteristic.getValue(), 8, 2);
                Integer zCorrectedInclination = getIntFromBytes(characteristic.getValue(), 10, 2);
                Integer xAcceleration = getIntFromBytes(characteristic.getValue(), 12, 1);
                Integer yAcceleration = getIntFromBytes(characteristic.getValue(), 13, 1);
                Integer zAcceleration = getIntFromBytes(characteristic.getValue(), 14, 1);
                Integer freqBin1 = getIntFromBytes(characteristic.getValue(), 16, 1);
                Integer freqBin2 = getIntFromBytes(characteristic.getValue(), 17, 1);
                Integer freqBin3 = getIntFromBytes(characteristic.getValue(), 18, 1);
                Integer freqBin4 = getIntFromBytes(characteristic.getValue(), 19, 1);
                if (listener != null)
                {
                    listener.cc_didUpdateAccelerometerValues(xInclination, xCorrectedInclination, xAcceleration,
                            yInclination, yCorrectedInclination, yAcceleration,
                            zInclination, zCorrectedInclination, zAcceleration,
                            freqBin1, freqBin2, freqBin3, freqBin4);
                }

                break;






            default:
                break;
        }


    }


    /* LISTENER INTERFACE */
    public interface CounterHDControllerListener {

        public void cc_didFindPeripherals(final List<BluetoothDevice> peripheralList);
        public void cc_didChangeConnectionState(final BluetoothGatt gatt,final int status,final int newState);
        public void cc_didReadCharacteristicValue(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic);
        public void cc_didChangeCharacteristicValue(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic);

        // basic Info
        public void cc_didUpdateBluetoothLocalName(final String name);
        public void cc_didUpdateMajorValue(final Integer major);
        public void cc_didUpdateMinorValue(final Integer minor);
        public void cc_didUpdateTXPower(final Integer txPower);

        // uuid
        public void cc_didUpdateUUID(final String uuid);

        // battery information
        public void cc_didUpdateBatteryInfo(final Integer chargePercent, boolean dcdcEndabled);

        // button state
        public void cc_didUpdateButton_1_state(boolean active);
        public void cc_didUpdateButton_2_state(boolean active);
        public void cc_didUpdateButton_3_state(boolean active);
        public void cc_didUpdateButton_4_state(boolean active);

        // Event Totals
        public void cc_didUpdateEventTotals(final Integer xEventCount, final Integer xEventTime, final Integer xProcessCount,
                                            final Integer yEventCount, final Integer yEventTime, final Integer yProcessCount,
                                            final Integer zEventCount, final Integer zEventTime, final Integer zProcessCount);

        // Device State
        public void cc_didUpdateDeviceType(final Integer devType);
        public void cc_didUpdateDeviceRevision(final Integer devRevision);
        public void cc_didUpdateBuildNumber(final Integer buildNumber);
        public void cc_didUpdateRS_Count(final Integer rs_Count);
        public void cc_didUpdateRS_Time(final Integer rs_Time);
        public void cc_didUpdateFirmwareVersion(final Integer major, final Integer minor);
        public void cc_didUpdateUserRole(final Integer userRole);
        public void cc_didUpdateStatusFlags(final  Integer statusFlags);

        // LIS Accellerometer
        public  void cc_didUpdateAccelerometerValues(final Integer xInclination, final Integer xCorrectedInclination, final Integer xGravity,
                                                     final Integer yInclination, final Integer yCorrectedInclination, final Integer yGravity,
                                                     final Integer zInclination, final Integer zCorrectedInclination, final Integer zGravity,
                                                     final Integer zFrequencyBin1,final Integer zFrequencyBin2,final Integer zFrequencyBin3,final Integer zFrequencyBin4 );

    }


    // Helping methods

    /**
     * @return Returns <b>true</b> if property is writable
     */
    private static boolean isCharacteristicWriteable(BluetoothGattCharacteristic pChar) {
        return (pChar.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
    }

    /**
     * @return Returns <b>true</b> if property is Readable
     */
    private static boolean isCharacterisitcReadable(BluetoothGattCharacteristic pChar) {
        return ((pChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0);
    }

    /**
     * @return Returns <b>true</b> if property is supports notification
     */
    private boolean isCharacterisiticNotifiable(BluetoothGattCharacteristic pChar) {
        return (pChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
    }

    private int getIntFromBytes(byte[] bytes, int start, int length)
    {
        int val = 0;
        switch (length){
            case 1:
                val = bytes[start];
                break;
            case 2:
                val = ((bytes[start +1] & 0xff) << 8) | (bytes[start] & 0xff);
                break;
            case 4:
                val = ((bytes[start +3] & 0xff) << 24) | ((bytes[start+2] & 0xff) << 16) | ((bytes[start+1] & 0xff) << 8) | (bytes[start] & 0xff);
        }
        return val;
    }
}
