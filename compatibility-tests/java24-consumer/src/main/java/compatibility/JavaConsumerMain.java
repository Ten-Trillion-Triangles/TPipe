package compatibility;

import com.TTT.PipeContextProtocol.KotlinExecutor;
import com.TTT.PipeContextProtocol.PcPRequest;
import com.TTT.PipeContextProtocol.PcpContext;
import com.TTT.PipeContextProtocol.PcpRequestResult;
import java.util.Collections;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlin.jvm.functions.Function2;

/** Verifies the public Java-visible KotlinExecutor ABI from a Java 24 app. */
public final class JavaConsumerMain {
    private JavaConsumerMain() {
    }

    public static void main(String[] args) throws Exception {
        KotlinExecutor executor = new KotlinExecutor();
        executor.registerBinding("hostValue", new JavaHostValue(4), "consumer host");
        if (KotlinExecutor.class.getConstructor() == null
                || KotlinExecutor.class.getMethod("registerBinding", String.class, Object.class, String.class) == null) {
            throw new AssertionError("KotlinExecutor public ABI changed");
        }

        executor.registerBinding("hostValue", new JavaHostValue(4), "consumer host");
        PcpContext context = new PcpContext();
        context.getKotlinOptions().setAllowHostApplicationAccess(true);
        context.getKotlinOptions().getExposedBindings().put("hostValue", "consumer host");
        PcPRequest request = new PcPRequest();
        request.setArgumentsOrFunctionParams(Collections.singletonList("hostValue.value"));
        PcpRequestResult execution = (PcpRequestResult) BuildersKt.runBlocking(
                EmptyCoroutineContext.INSTANCE,
                new Function2() {
                    @Override
                    public Object invoke(Object scope, Object continuation) {
                        return executor.execute(request, context, (Continuation) continuation);
                    }
                });
        if (!execution.getSuccess() || !"Result: 4".equals(execution.getOutput())) {
            throw new AssertionError("Java consumer script failed: " + execution.getError());
        }
        System.out.println("Java 24 consumer compatibility passed");
    }

    public static final class JavaHostValue {
        public final int value;

        public JavaHostValue(int value) {
            this.value = value;
        }
    }
}
