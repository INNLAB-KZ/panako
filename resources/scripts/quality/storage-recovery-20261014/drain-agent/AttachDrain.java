package be.panako.ops;

import com.sun.tools.attach.VirtualMachine;

public final class AttachDrain {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("PID, agent JAR, command required");
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try {
            vm.loadAgent(args[1], args[2]);
        } finally {
            vm.detach();
        }
    }
}
