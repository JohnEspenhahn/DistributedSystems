package gipcsims;

import java.rmi.Remote;

public interface Server extends Remote {
	void join(RspHandlerGIPCRemote r);
	void leave(RspHandlerGIPCRemote r);
	void broadcast(String msg, SimuMode mode, RspHandlerGIPCRemote src);
	
	void setMode(SimuMode m, RspHandlerGIPCRemote src);
}
