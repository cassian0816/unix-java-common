package exchange.antx.common.util;

import com.google.common.reflect.ClassPath;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Disabled
public class PbUtilTest {

    @Test
    public void testScanProto() throws IOException {
        List<String> nameList = new ArrayList<>();
        ClassPath cp = ClassPath.from(Thread.currentThread().getContextClassLoader());
        for (ClassPath.ClassInfo info : cp.getAllClasses()) {
            try {
                Class<?> clazz = info.load();
                Method method = clazz.getMethod("getDescriptor");
                if (method.getReturnType() == com.google.protobuf.Descriptors.FileDescriptor.class) {
                    nameList.add(clazz.getName());
                }
            } catch (NoClassDefFoundError | NoSuchMethodException | UnsupportedClassVersionError | VerifyError e) {
                // ignore
            }
        }
        Collections.sort(nameList);
        for (String name : nameList) {
            System.out.println("            .add(" + name + ".getDescriptor().getMessageTypes())");
        }
    }

}
