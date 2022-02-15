package test;

import com.lfp.joe.retrofit.client.RFit;
import com.lfp.pgbouncer.service.PGBouncerService;
import com.lfp.pgbouncer_app.ENVService;

public class ClientTest {

	public static void main(String[] args) {
		ENVService.init();
		var service = RFit.Clients.get(PGBouncerService.class);
		System.out.println(service.certificateChain());
	}
}
