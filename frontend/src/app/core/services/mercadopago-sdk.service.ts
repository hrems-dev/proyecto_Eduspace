import { Injectable } from '@angular/core';

/* eslint-disable @typescript-eslint/no-explicit-any */
declare global {
  interface Window { MercadoPago?: any; }
}

const SDK_URL = 'https://sdk.mercadopago.com/js/v2';

/**
 * Carga MercadoPago.js (una sola vez) y crea la instancia con la clave pública. El SDK tokeniza la tarjeta o Yape en el navegador:
 * nuestros servidores solo reciben el token, nunca los datos de la tarjeta.
 */
@Injectable({ providedIn: 'root' })
export class MercadoPagoSdkService {
  private carga: Promise<void> | null = null;
  private instancias = new Map<string, any>();

  async instancia(publicKey: string): Promise<any> {
    await this.cargar();
    let mp = this.instancias.get(publicKey);
    if (!mp) {
      mp = new window.MercadoPago!(publicKey, { locale: 'es-PE' });
      this.instancias.set(publicKey, mp);
    }
    return mp;
  }

  private cargar(): Promise<void> {
    if (window.MercadoPago) return Promise.resolve();
    if (!this.carga) {
      this.carga = new Promise<void>((ok, falla) => {
        const s = document.createElement('script');
        s.src = SDK_URL;
        s.async = true;
        s.onload = () => ok();
        s.onerror = () => { this.carga = null; falla(new Error('No se pudo cargar Mercado Pago. Revisa tu conexión.')); };
        document.head.appendChild(s);
      });
    }
    return this.carga;
  }
}
