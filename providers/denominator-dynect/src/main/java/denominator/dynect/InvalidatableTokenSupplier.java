package denominator.dynect;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import com.google.common.base.Supplier;

import denominator.Credentials;
import denominator.Credentials.ListCredentials;
import feign.Body;
import feign.RequestLine;

/**
 * gets the last auth token, expiring if the url or credentials changed
 */
// similar to guava MemoizingSupplier
@Singleton
class InvalidatableTokenSupplier implements Supplier<String> {
    interface Session {
        @RequestLine("POST /Session")
        @Body("%7B\"customer_name\":\"{customer_name}\",\"user_name\":\"{user_name}\",\"password\":\"{password}\"%7D")
        String login(@Named("customer_name") String customer, @Named("user_name") String user,
                @Named("password") String password);
    }

    private final denominator.Provider provider;
    private final Session session;
    private final Provider<Credentials> credentials;
    private final AtomicReference<Boolean> sessionValid;
    transient volatile String lastUrl;
    transient volatile int lastCredentialsHashCode;
    // "value" does not need to be volatile; visibility piggy-backs
    // on above
    transient String value;

    @Inject
    InvalidatableTokenSupplier(denominator.Provider provider, Session session,
            javax.inject.Provider<Credentials> credentials, AtomicReference<Boolean> sessionValid) {
        this.provider = provider;
        this.session = session;
        this.credentials = credentials;
        this.sessionValid = sessionValid;
        // for toString
        this.lastUrl = provider.url();
    }

    public void invalidate() {
        sessionValid.set(false);
    }

    @Override
    public String get() {
        String currentUrl = provider.url();
        Credentials currentCreds = credentials.get();

        if (needsRefresh(currentUrl, currentCreds)) {
            synchronized (this) {
                if (needsRefresh(currentUrl, currentCreds)) {
                    lastCredentialsHashCode = currentCreds.hashCode();
                    lastUrl = currentUrl;
                    String t = auth(currentCreds);
                    value = t;
                    sessionValid.set(true);
                    return t;
                }
            }
        }
        return value;
    }

    private boolean needsRefresh(String currentUrl, Credentials currentCreds) {
        return !sessionValid.get() || currentCreds.hashCode() != lastCredentialsHashCode || !currentUrl.equals(lastUrl);
    }

    private String auth(Credentials currentCreds) {
        List<Object> listCreds = ListCredentials.asList(currentCreds);
        return session.login(listCreds.get(0).toString(), listCreds.get(1).toString(), listCreds.get(2).toString());
    }

    @Override
    public String toString() {
        return "InvalidatableTokenSupplier(" + lastUrl + ")";
    }
}