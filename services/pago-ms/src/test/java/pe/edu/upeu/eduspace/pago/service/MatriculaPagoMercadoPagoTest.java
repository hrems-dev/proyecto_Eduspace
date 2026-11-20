package pe.edu.upeu.eduspace.pago.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pe.edu.upeu.eduspace.pago.client.MercadoPagoClient;
import pe.edu.upeu.eduspace.pago.client.AlumnosGateway;
import pe.edu.upeu.eduspace.pago.client.PlanificacionClient;
import pe.edu.upeu.eduspace.pago.config.PagoProperties;
import pe.edu.upeu.eduspace.pago.repository.CuotaRepository;
import pe.edu.upeu.eduspace.pago.repository.MatriculaLocalRepository;
import pe.edu.upeu.eduspace.pago.repository.OrdenPagoRepository;
import pe.edu.upeu.eduspace.pago.repository.SolicitudDescuentoRepository;
import pe.edu.upeu.eduspace.pago.service.MatriculaPagoService.Aviso;

class MatriculaPagoMercadoPagoTest {

    private MatriculaPagoService servicio() {
        return spy(new MatriculaPagoService(mock(CuotaRepository.class), mock(MatriculaLocalRepository.class), mock(OrdenPagoRepository.class),
            mock(SolicitudDescuentoRepository.class), mock(CreditoService.class), mock(AlumnosGateway.class), new PagoProperties(),
            mock(PlanificacionClient.class), mock(MercadoPagoClient.class), mock(PagoService.class), mock(pe.edu.upeu.eduspace.pago.repository.PagoRepository.class), mock(ComprobanteService.class)));
    }

    private static MercadoPagoClient.Pago pago(String estado, String tipo) {
        return new MercadoPagoClient.Pago(123L, estado, "accredited", "ORD-ABC", new BigDecimal("150.00"), "PEN", tipo, "visa", "Ana Quispe");
    }

    @Test
    void tarjetasSeGuardanComoTarjetaYLoDemasComoTransferencia() {
        assertThat(MatriculaPagoService.medioDe("credit_card")).isEqualTo("TARJETA");
        assertThat(MatriculaPagoService.medioDe("debit_card")).isEqualTo("TARJETA");
        assertThat(MatriculaPagoService.medioDe("account_money")).isEqualTo("TRANSFERENCIA");
        assertThat(MatriculaPagoService.medioDe("ticket")).isEqualTo("TRANSFERENCIA");
        assertThat(MatriculaPagoService.medioDe(null)).isEqualTo("TRANSFERENCIA");
    }

    @Test
    void pagoAprobadoSeConfirmaComoAvisoConElMontoDelPago() throws Exception {
        MatriculaPagoService s = servicio();
        mockConfirmar(s);
        s.procesarPagoMercadoPago(pago("approved", "credit_card"));

        ArgumentCaptor<Aviso> cap = ArgumentCaptor.forClass(Aviso.class);
        verify(s).confirmarOrden(cap.capture());
        Aviso a = cap.getValue();
        assertThat(a.ordenCodigo()).isEqualTo("ORD-ABC");
        assertThat(a.transaccionId()).isEqualTo("123");
        assertThat(a.estado()).isEqualTo("APROBADO");
        assertThat(a.monto()).isEqualByComparingTo("150.00");
        assertThat(a.medio()).isEqualTo("TARJETA");
    }

    @Test
    void pagoPendienteNoConfirmaNada() throws Exception {
        MatriculaPagoService s = servicio();
        mockConfirmar(s);
        s.procesarPagoMercadoPago(pago("in_process", "credit_card"));
        s.procesarPagoMercadoPago(pago("pending", "ticket"));
        verify(s, never()).confirmarOrden(any());
    }

    @Test
    void pagoRechazadoSeInformaSinCerrarLaOrden() throws Exception {
        MatriculaPagoService s = servicio();
        mockConfirmar(s);
        s.procesarPagoMercadoPago(pago("rejected", "credit_card"));
        ArgumentCaptor<Aviso> cap = ArgumentCaptor.forClass(Aviso.class);
        verify(s).confirmarOrden(cap.capture());
        assertThat(cap.getValue().estado()).isEqualTo("RECHAZADO");
    }


    @Test
    void avisoDePensionSeEnrutaAPagosYNoAMatricula() throws Exception {
        PagoService pagos = mock(PagoService.class);
        MatriculaPagoService s = spy(new MatriculaPagoService(mock(CuotaRepository.class), mock(MatriculaLocalRepository.class), mock(OrdenPagoRepository.class),
            mock(SolicitudDescuentoRepository.class), mock(CreditoService.class), mock(AlumnosGateway.class), new PagoProperties(),
            mock(PlanificacionClient.class), mock(MercadoPagoClient.class), pagos, mock(pe.edu.upeu.eduspace.pago.repository.PagoRepository.class), mock(ComprobanteService.class)));
        MercadoPagoClient.Pago pension = new MercadoPagoClient.Pago(55L, "approved", "accredited", "PG-42", new BigDecimal("80.00"), "PEN",
            "credit_card", "visa", "Ana");
        s.procesarPagoMercadoPago(pension);
        verify(pagos).confirmarPorPasarela(42L, new BigDecimal("80.00"));
        verify(s, never()).confirmarOrden(any());
    }

    /** Evita tocar la base: la confirmación real se prueba aparte (flujo de orden). */
    private static void mockConfirmar(MatriculaPagoService s) throws Exception {
        org.mockito.Mockito.doReturn(null).when(s).confirmarOrden(any());
    }
}
