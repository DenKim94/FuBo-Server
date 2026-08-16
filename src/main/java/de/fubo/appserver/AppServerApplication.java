package de.fubo.appserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Einstiegspunkt der Anwendung.
 *
 * <p><b>Zur Ausnahme von {@link UserDetailsServiceAutoConfiguration}:</b> Diese
 * Autokonfiguration legt einen In-Memory-Benutzer mit zufaelligem Passwort an und
 * protokolliert es beim Start ("Using generated security password"). Sie weicht nur
 * zurueck, wenn eine {@code AuthenticationManager}-, {@code AuthenticationProvider}-,
 * {@code UserDetailsService}- oder {@code AuthenticationManagerResolver}-Bean existiert -
 * eine eigene {@code SecurityFilterChain} genuegt ihr <b>nicht</b>. Der Benutzer ist hier
 * funktionslos, weil httpBasic und formLogin abgeschaltet sind; die Logzeile stiftet aber
 * dauerhaft Verwirrung und legt einen Anmeldeweg nahe, den es nicht gibt.
 *
 * <p><b>Nicht zu verwechseln mit {@code SecurityAutoConfiguration}.</b> Deren Ausschluss ist
 * laut {@code AGENT_SERVER.md} untersagt, weil damit die Deny-by-default-Haltung entfaellt.
 * Hier wird ausschliesslich der Dummy-Benutzer abgeschaltet; Filterchain, Autorisierung und
 * alle uebrigen Security-Bestandteile bleiben unveraendert.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AppServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(AppServerApplication.class, args);
	}

}
