#include "first.h"

#include "base.h"
#include "log.h"
#include "buffer.h"

#include "plugin.h"

#include "configfile.h"

#include <stdlib.h>
#include <string.h>
#include <errno.h>

#include "sys-socket.h"
#ifndef _WIN32
#include <netdb.h>
#endif

/**
 * mod_extforward.c for lighttpd, by comman.kang <at> gmail <dot> com
 *                  extended, modified by Lionel Elie Mamane (LEM), lionel <at> mamane <dot> lu
 *                  support chained proxies by glen@delfi.ee, #1528
 *
 * Config example:
 *
 *       Trust proxy 10.0.0.232 and 10.0.0.232
 *       extforward.forwarder = ( "10.0.0.232" => "trust",
 *                                "10.0.0.233" => "trust" )
 *
 *       Trust all proxies  (NOT RECOMMENDED!)
 *       extforward.forwarder = ( "all" => "trust")
 *
 *       Note that "all" has precedence over specific entries,
 *       so "all except" setups will not work.
 *
 *       In case you have chained proxies, you can add all their IP's to the
 *       config. However "all" has effect only on connecting IP, as the
 *       X-Forwarded-For header can not be trusted.
 *
 * Note: The effect of this module is variable on $HTTP["remotip"] directives and
 *       other module's remote ip dependent actions.
 *  Things done by modules before we change the remoteip or after we reset it will match on the proxy's IP.
 *  Things done in between these two moments will match on the real client's IP.
 *  The moment things are done by a module depends on in which hook it does things and within the same hook
 *  on whether they are before/after us in the module loading order
 *  (order in the server.modules directive in the config file).
 *
 * Tested behaviours:
 *
 *  mod_access: Will match on the real client.
 *
 *  mod_accesslog:
 *   In order to see the "real" ip address in access log ,
 *   you'll have to load mod_extforward after mod_accesslog.
 *   like this:
 *
 *    server.modules  = (
 *       .....
 *       mod_accesslog,
 *       mod_extforward
 *    )
 */


/* plugin config for all request/connections */

typedef struct {
	array *forwarder;
	array *headers;
} plugin_config;

typedef struct {
	PLUGIN_DATA;

	plugin_config **config_storage;

	plugin_config conf;
} plugin_data;


/* context , used for restore remote ip */

typedef struct {
	sock_addr saved_remote_addr;
	buffer *saved_remote_addr_buf;
} handler_ctx;


static handler_ctx * handler_ctx_init(void) {
	handler_ctx * hctx;
	hctx = calloc(1, sizeof(*hctx));
	return hctx;
}

static void handler_ctx_free(handler_ctx *hctx) {
	free(hctx);
}

/* init the plugin data */
INIT_FUNC(mod_extforward_init) {
	plugin_data *p;
	p = calloc(1, sizeof(*p));
	return p;
}

/* destroy the plugin data */
FREE_FUNC(mod_extforward_free) {
	plugin_data *p = p_d;

	UNUSED(srv);

	if (!p) return HANDLER_GO_ON;

	if (p->config_storage) {
		size_t i;

		for (i = 0; i < srv->config_context->used; i++) {
			plugin_config *s = p->config_storage[i];

			if (NULL == s) continue;

			array_free(s->forwarder);
			array_free(s->headers);

			free(s);
		}
		free(p->config_storage);
	}


	free(p);

	return HANDLER_GO_ON;
}

/* handle plugin config and check values */

SETDEFAULTS_FUNC(mod_extforward_set_defaults) {
	plugin_data *p = p_d;
	size_t i = 0;

	config_values_t cv[] = {
		{ "extforward.forwarder",       NULL, T_CONFIG_ARRAY, T_CONFIG_SCOPE_CONNECTION },       /* 0 */
		{ "extforward.headers",         NULL, T_CONFIG_ARRAY, T_CONFIG_SCOPE_CONNECTION },       /* 1 */
		{ NULL,                         NULL, T_CONFIG_UNSET, T_CONFIG_SCOPE_UNSET }
	};

	if (!p) return HANDLER_ERROR;

	p->config_storage = calloc(1, srv->config_context->used * sizeof(plugin_config *));

	for (i = 0; i < srv->config_context->used; i++) {
		data_config const* config = (data_config const*)srv->config_context->data[i];
		plugin_config *s;

		s = calloc(1, sizeof(plugin_config));
		s->forwarder    = array_init();
		s->headers      = array_init();

		cv[0].destination = s->forwarder;
		cv[1].destination = s->headers;

		p->config_storage[i] = s;

		if (0 != config_insert_values_global(srv, config->value, cv, i == 0 ? T_CONFIG_SCOPE_SERVER : T_CONFIG_SCOPE_CONNECTION)) {
			return HANDLER_ERROR;
		}

		if (!array_is_kvstring(s->forwarder)) {
			log_error_write(srv, __FILE__, __LINE__, "s",
					"unexpected value for extforward.forwarder; expected list of \"IPaddr\" => \"trust\"");
			return HANDLER_ERROR;
		}

		if (!array_is_vlist(s->headers)) {
			log_error_write(srv, __FILE__, __LINE__, "s",
					"unexpected value for extforward.headers; expected list of \"headername\"");
			return HANDLER_ERROR;
		}

		/* default to "X-Forwarded-For" or "Forwarded-For" if extforward.headers not specified or empty */
		if (0 == s->headers->used && (0 == i || NULL != array_get_element(config->value, "extforward.headers"))) {
			data_string *ds;
			ds = data_string_init();
			buffer_copy_string_len(ds->value, CONST_STR_LEN("X-Forwarded-For"));
			array_insert_unique(s->headers, (data_unset *)ds);
			ds = data_string_init();
			buffer_copy_string_len(ds->value, CONST_STR_LEN("Forwarded-For"));
			array_insert_unique(s->headers, (data_unset *)ds);
		}
	}

	return HANDLER_GO_ON;
}

#define PATCH(x) \
	p->conf.x = s->x;
static int mod_extforward_patch_connection(server *srv, connection *con, plugin_data *p) {
	size_t i, j;
	plugin_config *s = p->config_storage[0];

	PATCH(forwarder);
	PATCH(headers);

	/* skip the first, the global context */
	for (i = 1; i < srv->config_context->used; i++) {
		data_config *dc = (data_config *)srv->config_context->data[i];
		s = p->config_storage[i];

		/* condition didn't match */
		if (!config_check_cond(srv, con, dc)) continue;

		/* merge config */
		for (j = 0; j < dc->value->used; j++) {
			data_unset *du = dc->value->data[j];

			if (buffer_is_equal_string(du->key, CONST_STR_LEN("extforward.forwarder"))) {
				PATCH(forwarder);
			} else if (buffer_is_equal_string(du->key, CONST_STR_LEN("extforward.headers"))) {
				PATCH(headers);
			}
		}
	}

	return 0;
}
#undef PATCH


static void put_string_into_array_len(array *ary, const char *str, int len)
{
	data_string *tempdata;
	if (len == 0)
		return;
	tempdata = data_string_init();
	buffer_copy_string_len(tempdata->value,str,len);
	array_insert_unique(ary,(data_unset *)tempdata);
}
/*
   extract a forward array from the environment
*/
static array *extract_forward_array(buffer *pbuffer)
{
	array *result = array_init();
	if (!buffer_string_is_empty(pbuffer)) {
		char *base, *curr;
		/* state variable, 0 means not in string, 1 means in string */
		int in_str = 0;
		for (base = pbuffer->ptr, curr = pbuffer->ptr; *curr; curr++) {
			if (in_str) {
				if ((*curr > '9' || *curr < '0') && *curr != '.' && *curr != ':' && (*curr < 'a' || *curr > 'f') && (*curr < 'A' || *curr > 'F')) {
					/* found an separator , insert value into result array */
					put_string_into_array_len(result, base, curr - base);
					/* change state to not in string */
					in_str = 0;
				}
			} else {
				if ((*curr >= '0' && *curr <= '9') || *curr == ':' || (*curr >= 'a' && *curr <= 'f') || (*curr >= 'A' && *curr <= 'F')) {
					/* found leading char of an IP address, move base pointer and change state */
					base = curr;
					in_str = 1;
				}
			}
		}
		/* if breaking out while in str, we got to the end of string, so add it */
		if (in_str) {
			put_string_into_array_len(result, base, curr - base);
		}
	}
	return result;
}

#define IP_TRUSTED 1
#define IP_UNTRUSTED 0
/*
 * check whether ip is trusted, return 1 for trusted , 0 for untrusted
 */
static int is_proxy_trusted(const char *ipstr, plugin_data *p)
{
	data_string* allds = (data_string *)array_get_element(p->conf.forwarder, "all");

	if (allds) {
		if (strcasecmp(allds->value->ptr, "trust") == 0) {
			return IP_TRUSTED;
		} else {
			return IP_UNTRUSTED;
		}
	}

	return (data_string *)array_get_element(p->conf.forwarder, ipstr) ? IP_TRUSTED : IP_UNTRUSTED;
}

/*
 * Return char *ip of last address of proxy that is not trusted.
 * Do not accept "all" keyword here.
 */
static const char *last_not_in_array(array *a, plugin_data *p)
{
	array *forwarder = p->conf.forwarder;
	int i;

	for (i = a->used - 1; i >= 0; i--) {
		data_string *ds = (data_string *)a->data[i];
		const char *ip = ds->value->ptr;

		if (!array_get_element(forwarder, ip)) {
			return ip;
		}
	}
	return NULL;
}

static void ipstr_to_sockaddr(server *srv, const char *host, sock_addr *sock) {
#ifdef HAVE_IPV6
	struct addrinfo hints, *addrlist = NULL;
	int result;

	memset(&hints, 0, sizeof(hints));
	sock->plain.sa_family = AF_UNSPEC;

#ifndef AI_NUMERICSERV
	/**
	  * quoting $ man getaddrinfo
	  *
	  * NOTES
	  *        AI_ADDRCONFIG, AI_ALL, and AI_V4MAPPED are available since glibc 2.3.3.
	  *        AI_NUMERICSERV is available since glibc 2.3.4.
	  */
#define AI_NUMERICSERV 0
#endif
	hints.ai_flags = AI_NUMERICHOST | AI_NUMERICSERV;

	errno = 0;
	result = getaddrinfo(host, NULL, &hints, &addrlist);

	if (result != 0) {
		log_error_write(srv, __FILE__, __LINE__, "SSSs(S)",
			"could not parse ip address ", host, " because ", gai_strerror(result), strerror(errno));
	} else if (addrlist == NULL) {
		log_error_write(srv, __FILE__, __LINE__, "SSS",
			"Problem in parsing ip address ", host, ": succeeded, but no information returned");
	} else switch (addrlist->ai_family) {
	case AF_INET:
		memcpy(&sock->ipv4, addrlist->ai_addr, sizeof(sock->ipv4));
		force_assert(AF_INET == sock->plain.sa_family);
		break;
	case AF_INET6:
		memcpy(&sock->ipv6, addrlist->ai_addr, sizeof(sock->ipv6));
		force_assert(AF_INET6 == sock->plain.sa_family);
		break;
	default:
		log_error_write(srv, __FILE__, __LINE__, "SSS",
			"Problem in parsing ip address ", host, ": succeeded, but unknown family");
	}

	freeaddrinfo(addrlist);
#else
	UNUSED(srv);
	sock->ipv4.sin_addr.s_addr = inet_addr(host);
	sock->plain.sa_family = (sock->ipv4.sin_addr.s_addr == 0xFFFFFFFF) ? AF_UNSPEC : AF_INET;
#endif
}

static void clean_cond_cache(server *srv, connection *con) {
	config_cond_cache_reset_item(srv, con, COMP_HTTP_REMOTE_IP);
	config_cond_cache_reset_item(srv, con, COMP_HTTP_SCHEME);
}

static int mod_extforward_set_addr(server *srv, connection *con, plugin_data *p, const char *addr) {
	sock_addr sock;
	handler_ctx *hctx;

	if (con->conf.log_request_handling) {
		log_error_write(srv, __FILE__, __LINE__, "ss", "using address:", addr);
	}

	sock.plain.sa_family = AF_UNSPEC;
	ipstr_to_sockaddr(srv, addr, &sock);
	if (sock.plain.sa_family == AF_UNSPEC) return 0;

	/* we found the remote address, modify current connection and save the old address */
	if (con->plugin_ctx[p->id]) {
		if (con->conf.log_request_handling) {
			log_error_write(srv, __FILE__, __LINE__, "s",
				"-- mod_extforward_uri_handler already patched this connection, resetting state");
		}
		handler_ctx_free(con->plugin_ctx[p->id]);
		con->plugin_ctx[p->id] = NULL;
	}
	/* save old address */
	con->plugin_ctx[p->id] = hctx = handler_ctx_init();
	hctx->saved_remote_addr = con->dst_addr;
	hctx->saved_remote_addr_buf = con->dst_addr_buf;
	/* patch connection address */
	con->dst_addr = sock;
	con->dst_addr_buf = buffer_init_string(addr);

	if (con->conf.log_request_handling) {
		log_error_write(srv, __FILE__, __LINE__, "ss",
				"patching con->dst_addr_buf for the accesslog:", addr);
	}

	/* Now, clean the conf_cond cache, because we may have changed the results of tests */
	clean_cond_cache(srv, con);

	return 1;
}

static void mod_extforward_set_proto(connection *con, const char *proto, size_t protolen) {
	if (0 != protolen && !buffer_is_equal_caseless_string(con->uri.scheme, proto, protolen)) {
		/* update scheme if X-Forwarded-Proto is set
		 * Limitations:
		 * - Only "http" or "https" are currently accepted since the request to lighttpd currently has to
		 *   be HTTP/1.0 or HTTP/1.1 using http or https.  If this is changed, then the scheme from this
		 *   untrusted header must be checked to contain only alphanumeric characters, and to be a
		 *   reasonable length, e.g. < 256 chars.
		 * - con->uri.scheme is not reset in mod_extforward_restore() but is currently not an issues since
		 *   con->uri.scheme will be reset by next request.  If a new module uses con->uri.scheme in the
		 *   handle_request_done hook, then should evaluate if that module should use the forwarded value
		 *   (probably) or the original value.
		 */
		if (0 == buffer_caseless_compare(proto, protolen, CONST_STR_LEN("https"))) {
			buffer_copy_string_len(con->uri.scheme, CONST_STR_LEN("https"));
		} else if (0 == buffer_caseless_compare(proto, protolen, CONST_STR_LEN("http"))) {
			buffer_copy_string_len(con->uri.scheme, CONST_STR_LEN("http"));
		}
	}
}

static handler_t mod_extforward_X_Forwarded_For(server *srv, connection *con, plugin_data *p, buffer *x_forwarded_for) {
	/* build forward_array from forwarded data_string */
	array *forward_array = extract_forward_array(x_forwarded_for);
	const char *real_remote_addr = last_not_in_array(forward_array, p);
	if (real_remote_addr != NULL) { /* parsed */
		/* get scheme if X-Forwarded-Proto is set
		 * Limitations:
		 * - X-Forwarded-Proto may or may not be set by proxies, even if X-Forwarded-For is set
		 * - X-Forwarded-Proto may be a comma-separated list if there are multiple proxies,
		 *   but the historical behavior of the code below only honored it if there was exactly one value
		 *   (not done: walking backwards in X-Forwarded-Proto the same num of steps
		 *    as in X-Forwarded-For to find proto set by last trusted proxy)
		 */
		data_string *x_forwarded_proto = (data_string *)array_get_element(con->request.headers, "X-Forwarded-Proto");
		if (mod_extforward_set_addr(srv, con, p, real_remote_addr) && NULL != x_forwarded_proto) {
			mod_extforward_set_proto(con, CONST_BUF_LEN(x_forwarded_proto->value));
		}
	}
	array_free(forward_array);
	return HANDLER_GO_ON;
}

URIHANDLER_FUNC(mod_extforward_uri_handler) {
	plugin_data *p = p_d;
	data_string *forwarded = NULL;

	mod_extforward_patch_connection(srv, con, p);

	if (con->conf.log_request_handling) {
		log_error_write(srv, __FILE__, __LINE__, "s",
			"-- mod_extforward_uri_handler called");
	}

	for (size_t k = 0; k < p->conf.headers->used && NULL == forwarded; ++k) {
		forwarded = (data_string *) array_get_element(con->request.headers, ((data_string *)p->conf.headers->data[k])->value->ptr);
	}
	if (NULL == forwarded) {
		if (con->conf.log_request_handling) {
			log_error_write(srv, __FILE__, __LINE__, "s", "no forward header found, skipping");
		}

		return HANDLER_GO_ON;
	}

	/* if the remote ip itself is not trusted, then do nothing */
	if (IP_UNTRUSTED == is_proxy_trusted(con->dst_addr_buf->ptr, p)) {
		if (con->conf.log_request_handling) {
			log_error_write(srv, __FILE__, __LINE__, "sbs",
					"remote address", con->dst_addr_buf, "is NOT a trusted proxy, skipping");
		}

		return HANDLER_GO_ON;
	}

	return mod_extforward_X_Forwarded_For(srv, con, p, forwarded->value);
}

CONNECTION_FUNC(mod_extforward_restore) {
	plugin_data *p = p_d;
	handler_ctx *hctx = con->plugin_ctx[p->id];

	if (!hctx) return HANDLER_GO_ON;
	
	con->dst_addr = hctx->saved_remote_addr;
	buffer_free(con->dst_addr_buf);

	con->dst_addr_buf = hctx->saved_remote_addr_buf;
	
	handler_ctx_free(hctx);

	con->plugin_ctx[p->id] = NULL;

	/* Now, clean the conf_cond cache, because we may have changed the results of tests */
	clean_cond_cache(srv, con);

	return HANDLER_GO_ON;
}


/* this function is called at dlopen() time and inits the callbacks */

int mod_extforward_plugin_init(plugin *p);
int mod_extforward_plugin_init(plugin *p) {
	p->version     = LIGHTTPD_VERSION_ID;
	p->name        = buffer_init_string("extforward");

	p->init        = mod_extforward_init;
	p->handle_uri_raw = mod_extforward_uri_handler;
	p->handle_request_done = mod_extforward_restore;
	p->connection_reset = mod_extforward_restore;
	p->set_defaults  = mod_extforward_set_defaults;
	p->cleanup     = mod_extforward_free;

	p->data        = NULL;

	return 0;
}

