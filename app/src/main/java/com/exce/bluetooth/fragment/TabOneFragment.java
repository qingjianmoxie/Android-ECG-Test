package com.exce.bluetooth.fragment;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.exce.bluetooth.R;
import com.exce.bluetooth.activity.ble.BLEActivity;
import com.exce.bluetooth.activity.usb.USBActivity;
import com.exce.bluetooth.bean.MyField;
import com.exce.bluetooth.bean.UserInfo;
import com.exce.bluetooth.utils.MyObjIterator;
import com.exce.bluetooth.utils.SharedPreferenceUtil;
import com.exce.bluetooth.utils.TypeUntils;
import com.exce.bluetooth.view.EcgView;
import com.google.common.primitives.Shorts;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @Author Wangjj
 * @Create 2017/12/21.
 * @Content
 */
public class TabOneFragment extends Fragment {


    /**
     * ------------心电-----------
     */
    private BlockingQueue<Float[]> data0Q = new LinkedBlockingQueue<>();
    private BlockingQueue<Byte> dataB = new LinkedBlockingQueue<>();
    //数据处理线程
    private boolean dataHandThread_isRunning = false;

    //------------------------------------------
    private View mRootView;
    Toolbar mToolBar;

    public TabOneFragment() {
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        mRootView = inflater.inflate(R.layout.fragment_home, container, false);
        // Activity按钮事件中
        new GetLogTask().execute();
        simulator();
        init(mRootView);
        return mRootView;
    }

    private void init(View view) {
        mToolBar = view.findViewById(R.id.tool_bar);
        mToolBar.inflateMenu(R.menu.toolbar_menu);
        mToolBar.setOnMenuItemClickListener(item -> {
            //在这里执行我们的逻辑代码
            switch (item.getItemId()) {
                case R.id.change_usb:
                    Intent intent1 = new Intent(getContext(), USBActivity.class);
                    startActivity(intent1);
                    Toast.makeText(getContext(), "settings", Toast.LENGTH_SHORT).show();
                    break;
                case R.id.change_ble:
                    Intent intent2 = new Intent(getContext(), BLEActivity.class);
                    startActivity(intent2);
                    Toast.makeText(getContext(), "settings", Toast.LENGTH_SHORT).show();
                    break;
                default:
                    break;
            }
            return false;
        });
    }


    /**
     * socket
     */
    public class GetLogTask extends AsyncTask<Void, Void, String> {

        @Override
        protected String doInBackground(Void... voids) {
            try {
                Socket s = new Socket("10.1.1.251", 12306);
                System.out.println("连接成功......");
                OutputStream ous = s.getOutputStream();
                DataOutputStream dos = new DataOutputStream(ous);
                //采集信息  //发送数据指令 发送回调
                UserInfo ui = SharedPreferenceUtil.getUser(getContext(), "ecg", "config_msg");
                byte[] data = mParse(ui);
                dos.write(data);


                InputStream is = s.getInputStream();
                DataInputStream dis = new DataInputStream(is);
                startDataHandThread();
                int len;
                byte[] buffer = new byte[4096];
                while ((len = dis.read(buffer)) != -1) {
                    for (int i = 0; i < len; i++) {
                        dataB.add(buffer[i]);
                        System.out.println("------------------------------*************--------------------" + buffer[i]);
                    }
                }
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            return "";
        }
    }

    /**
     * 开启数据处理线程
     */
    private void startDataHandThread() {
        if (!dataHandThread_isRunning) {
            dataHandThread_isRunning = true;
            new Thread(() -> {
                byte[] buffer = new byte[4096];
                int len;
                while (dataHandThread_isRunning) {
                    // 取协议头
                    byte b;
                    b = dequeue(dataB);
                    if (b != unsigned_byte(0xaa)) continue;
                    b = dequeue(dataB);
                    if (b != unsigned_byte(0xaa)) continue;
                    // 取总长度
                    for (int i = 0; i < 2; i++) {
                        buffer[i] = dequeue(dataB);
                    }
                    len = Shorts.fromBytes(buffer[0], buffer[1]);

                    // 取剩下的
                    for (int i = 0; i < len; i++) {
                        buffer[i] = dequeue(dataB);
                    }

                    // 判断协议完整性(判断尾或crc)
                    if (buffer[len - 2] != unsigned_byte(0x55)) continue;
                    if (buffer[len - 1] != unsigned_byte(0x55)) continue;

                    // -------------判断帧类型-------------------------
                    // 帧类型
                    // TODO 判断帧类型，这里默认为数据
                    if (buffer[0] != unsigned_byte(0x32)) continue;

                    //--------以下为数据帧解析---------------
                    // 数据长度
                    short datalen = Shorts.fromBytes(buffer[1], buffer[2]);
                    // 数据
                    Float[] f = new Float[12];
                    for (int i = 0; i < datalen / 2; i++) {
                        f[i] = (float) Shorts.fromBytes(buffer[2 * i + 3], buffer[2 * i + 4]);
                    }
                    data0Q.add(f);
                }
            }).start();
        }
    }


    /**
     * 用自定义协议包装
     *
     * @param ui 包装对象
     * @return byte[] 包装后的对象
     */
    public byte[] mParse(UserInfo ui) {
        MyObjIterator iterator = new MyObjIterator(ui);
        byte[] allIns = null;
        while (iterator.hasNext()) {
            MyField field = iterator.next();
            if (field.getValue() == null) continue;
            String name = field.getName();
            byte[] insHead = getInsHead(name); // 指令头
            byte[] value = objToByte(field.getValue(), field.getType()); // 指令数据
            short valueLen = (short) value.length;
            byte[] valueLenBuffer = Shorts.toByteArray(valueLen); // 指令数据长度
            byte[] ins = TypeUntils.byteAppend(insHead, valueLenBuffer, value); // 得到单条指令
            allIns = TypeUntils.byteAppend(allIns, ins); // 追加指令到指令集合中
        }
        byte[] head = new byte[]{TypeUntils.unsigned_byte(0xaa), TypeUntils.unsigned_byte(0xaa)}; // 头
        int allInsLen = (allIns == null) ? 0 : allIns.length;
        byte[] allLen = Shorts.toByteArray((short) (allInsLen + 3)); // 总长度
        byte[] type = new byte[]{TypeUntils.unsigned_byte(0x30)}; // 帧类型
        byte[] end = new byte[]{TypeUntils.unsigned_byte(0x55), TypeUntils.unsigned_byte(0x55)}; // 结束字
        return TypeUntils.byteAppend(head, allLen, type, allIns, end);
    }

    /**
     * 对比UserInfo  更新
     *
     * @param old
     * @param newU
     * @return
     */
    public UserInfo compare(UserInfo old, UserInfo newU) {
        UserInfo u = new UserInfo();

        if (!newU.getOpenId().equals(old.getOpenId())) {
            u.setOpenId(newU.getOpenId());
        }
        if (newU.getAge() == newU.getAge()) {
            u.setAge(newU.getAge());
        }
        if (newU.getHeight() == newU.getHeight()) {
            u.setHeight(newU.getHeight());
        }
        if (!newU.getUserName().equals(newU.getUserName())) {
            u.setUserName(newU.getUserName());
        }
        if (newU.getSex() == newU.getSex()) {
            u.setSex(newU.getSex());
        }
        if (newU.getWeight() == newU.getWeight()) {
            u.setWeight(newU.getWeight());
        }
        if (!newU.getPhone().equals(newU.getPhone())) {
            u.setPhone(newU.getPhone());
        }
        if (!newU.getCid().equals(newU.getCid())) {
            u.setCid(newU.getCid());
        }
        if (newU.getSampleSpeed() == newU.getSampleSpeed()) {
            u.setSampleSpeed(newU.getSampleSpeed());
        }
        if (newU.getGain() == newU.getGain()) {
            u.setGain(newU.getGain());
        }
        if (newU.getPatientType() == newU.getPatientType()) {
            u.setPatientType(newU.getPatientType());
        }
        if (newU.getDisplayLines() == newU.getDisplayLines()) {
            u.setDisplayLines(newU.getDisplayLines());
        }

        return u;
    }

    /**
     * 对象转byte数组（自定义）
     *
     * @param obj
     * @param type
     * @return byte[]
     */
    public byte[] objToByte(Object obj, Type type) {
        byte[] b = null;
        if (type == String.class) {
            b = String.valueOf(obj).getBytes(StandardCharsets.UTF_8);
        } else if (type == byte.class) {
            b = new byte[]{(byte) obj};
        } else if (type == short.class) {
            b = Shorts.toByteArray((short) obj);
        } else if (type == float.class) {
            b = TypeUntils.float2Bytes((float) obj);
        }
        return b;
    }

    /**
     * 获取指令头
     *
     * @param name
     * @return 指令
     */
    public byte[] getInsHead(String name) {
        byte[] b = new byte[2];
        switch (name) {
            case "openId":
                b[0] = TypeUntils.unsigned_byte(0x00);
                b[1] = TypeUntils.unsigned_byte(0x01);
                break;
            case "age":
                b[0] = TypeUntils.unsigned_byte(0x00);
                b[1] = TypeUntils.unsigned_byte(0x02);
                break;
            case "height":
                b[0] = TypeUntils.unsigned_byte(0x00);
                b[1] = TypeUntils.unsigned_byte(0x03);
                break;
            case "userName":
                b[0] = TypeUntils.unsigned_byte(0x00);
                b[1] = TypeUntils.unsigned_byte(0x04);
                break;
            case "sex":
                b[0] = TypeUntils.unsigned_byte(0x00);
                b[1] = TypeUntils.unsigned_byte(0x05);
                break;
            case "weight":
                b[0] = TypeUntils.unsigned_byte(0x00);
                b[1] = TypeUntils.unsigned_byte(0x06);
                break;
            case "phone":
                b[0] = TypeUntils.unsigned_byte(0x00);
                b[1] = TypeUntils.unsigned_byte(0x07);
                break;
            case "cid":
                b[0] = TypeUntils.unsigned_byte(0x00);
                b[1] = TypeUntils.unsigned_byte(0x08);
                break;
            case "sampleSpeed":
                b[0] = TypeUntils.unsigned_byte(0x00);
                b[1] = TypeUntils.unsigned_byte(0x0a);
                break;
            case "gain":
                b[0] = TypeUntils.unsigned_byte(0x00);
                b[1] = TypeUntils.unsigned_byte(0x0b);
                break;
            case "patientType":
                b[0] = TypeUntils.unsigned_byte(0x00);
                b[1] = TypeUntils.unsigned_byte(0x0c);
                break;
            case "displayLines":
                b[0] = TypeUntils.unsigned_byte(0x00);
                b[1] = TypeUntils.unsigned_byte(0x0d);
                break;
            default:
                throw new RuntimeException("未知的指令名");
        }
        return b;
    }


    /**
     * 取出队列中的一个数据
     *
     * @param queue
     * @return
     */
    private byte dequeue(BlockingQueue<Byte> queue) {
        Byte b = null;
        do {
            b = queue.poll();
            if (b == null) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        } while (b == null);
        return b;
    }

    /**
     * 模拟unsigned 的 byte类型
     *
     * @param i
     * @return
     */
    private byte unsigned_byte(int i) {
        if (i > 255 || i < 0) {
            throw new RuntimeException("i 必须在 0x00 - 0xff 之间");
        }
        if (i > 127) {
            return (byte) (i - 256);
        } else {
            return (byte) i;
        }
    }


    /**
     * 显示心电图入口
     */
    private void simulator() {
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                if (EcgView.isRunning) {
                    if (data0Q.size() > 0) {
                        EcgView.addEcgData0(data0Q.poll());
                    }
                }
            }
        }, 0, 2);
    }

}
