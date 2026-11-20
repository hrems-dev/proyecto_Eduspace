import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DescuentoVista, InicioMatricula, OfertaMatricula, ResumenMatricula, SolicitudMatricula, Tarjeta } from '../models/matricula-publica.model';
import { DatosCobroMp, ResultadoMp } from '../../shared/components/mercadopago-checkout/mercadopago-checkout.component';

const API = '/api/v1/publico/matricula';
const TOKEN = 'X-Matricula-Token';

/** Matrícula en línea del sitio público: no usa la sesión; cada trámite se identifica con su referencia y su token. */
@Injectable({ providedIn: 'root' })
export class MatriculaPublicaService {
  private readonly http = inject(HttpClient);

  oferta(): Observable<OfertaMatricula> {
    return this.http.get<OfertaMatricula>(`${API}/oferta`);
  }

  /** Valida un código de descuento (404 si no existe o ya no está vigente). */
  descuento(codigo: string): Observable<DescuentoVista> {
    return this.http.get<DescuentoVista>(`${API}/descuento/${encodeURIComponent(codigo)}`);
  }

  iniciar(s: SolicitudMatricula): Observable<InicioMatricula> {
    return this.http.post<InicioMatricula>(API, s);
  }

  /** Pasarela de pago de demostración: no se cobra nada. */
  /** Con Mercado Pago no se envía la tarjeta: el resumen trae la URL donde pagar. */
  pagar(referencia: string, token: string, tarjeta: Tarjeta | null): Observable<ResumenMatricula> {
    return this.http.post<ResumenMatricula>(`${API}/${referencia}/pagar`, { tarjeta }, { headers: new HttpHeaders({ [TOKEN]: token }) });
  }

  /** Cobro con Mercado Pago en nuestra pantalla (tarjeta o Yape): solo viaja el token de MercadoPago.js. */
  pagarMercadoPago(referencia: string, token: string, datos: DatosCobroMp): Observable<ResultadoMp & { resumen: ResumenMatricula }> {
    return this.http.post<ResultadoMp & { resumen: ResumenMatricula }>(`${API}/${referencia}/pagar-mercadopago`, datos,
      { headers: new HttpHeaders({ [TOKEN]: token }) });
  }

  /** El comprobante (voucher) en PDF del pago de la matrícula con Mercado Pago. */
  comprobante(referencia: string, token: string): Observable<Blob> {
    return this.http.get(`${API}/${referencia}/comprobante`, { headers: new HttpHeaders({ [TOKEN]: token }), responseType: 'blob' });
  }

  /** Qué pasarela usa la matrícula en línea: Mercado Pago real o la de demostración, y su clave pública. */
  pasarela(): Observable<{ mercadopago: boolean; modo: string; publicKey?: string }> {
    return this.http.get<{ mercadopago: boolean; modo: string; publicKey?: string }>(`${API}/pasarela`);
  }

  resumen(referencia: string, token: string): Observable<ResumenMatricula> {
    return this.http.get<ResumenMatricula>(`${API}/${referencia}`, { headers: new HttpHeaders({ [TOKEN]: token }) });
  }

  constancia(referencia: string, token: string): Observable<Blob> {
    return this.http.get(`${API}/${referencia}/constancia`, { headers: new HttpHeaders({ [TOKEN]: token }), responseType: 'blob' });
  }
}
