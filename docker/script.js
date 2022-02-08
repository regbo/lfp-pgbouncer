const {
	spawnSync
} = require('child_process');
const crypto = require('crypto');
const assert = require('assert');
const fs = require('fs');
const processEnv = process.env;
const fileUpload = require('express-fileupload');
const DEBUG = processEnv["DEBUG"] == "true";
const PSQL_PATH = "/opt/bitnami/postgresql/bin/psql";
const CONF_PATH = "/opt/bitnami/pgbouncer/conf";
const INI_PATH = `${CONF_PATH}/pgbouncer.ini`;

function isBlank(str) {
	return (!str || /^\s*$/.test(str));
}
function env(name, fallback, parser) {
	if (isBlank(name))
		return fallback;
	var value = processEnv[name];
	if (parser != null && !isBlank(value))
		value = parser(value);
	if (value == null || (typeof str === 'string' && isBlank(value)))
		value = fallback;
	if (DEBUG)
		console.log(`${name}: ${value}`);
	return value
}
function reload() {
	if (DEBUG)
		console.log(`starting reload`)
		const proc = spawnSync('su', ["-c", `echo "RELOAD" | "${PSQL_PATH}" -p 6432 pgbouncer`, "pgbouncer"], {
				env: env
			});
	var stdout = proc.stdout.toString().trim();
	var stderr = proc.stderr.toString().trim();
	if (DEBUG)
		console.log(`stdout:\n${stdout}`);
	if (DEBUG)
		console.log(`stderr:\n${stderr}`);
	assert("reload" == stdout.toLowerCase());
	for (var out of[stdout, stderr]) {
		if (!isBlank(out))
			return out;
	}
	return null;
}

function handle(request, response) {
	var data = {};
	if (request.files) {
		for (var fileId in request.files) {
			var file = request.files[fileId];
			var outFile = CONF_PATH + `/${fileId}`;
			file.mv(outFile);
			data[fileId] = outFile;
		}
	}
	for (var params of[request.body, request.query]) {
		if (params == null)
			continue;
		for (var param in params)
			data[param] = params[param];
	}
	if (DEBUG)
		console.log(data);
	const currentData = fs.readFileSync(INI_PATH, 'utf-8');
	var newDataLines = [];
	for (var line of currentData.split(/\r?\n/)) {
		if (isBlank(line))
			continue;
		var remove = false;
		for (var key of Object.keys(data)) {
			var value = data[key];
			if (line.indexOf(key + "=") == 0) {
				remove = true;
				break;
			}
		}
		if (remove)
			continue;
		newDataLines.push(line);
	}
	for (var key of Object.keys(data)) {
		var value = data[key];
		if (!isBlank(value))
			newDataLines.push(`${key}=${value}`)

	}
	var newData = newDataLines.join('\n');
	if (DEBUG)
		console.log(newData);
	fs.writeFileSync(INI_PATH,
		newData, {
		encoding: "utf8"
	});
	response.send(reload());

}

var apiUsername = env("API_USERNAME", "");
var apiPassword = env("API_PASSWORD", "");

var apiPort = env("API_PORT", 6488, v => parseInt(v));

const app = require('express')();
// enable files upload
app.use(fileUpload({
		createParentPath: true
	}));
//enable basic auth
if (!isBlank(apiUsername) || !isBlank(apiPassword)) {
	const basicAuth = require('express-basic-auth');
	const users = {};
	users[apiUsername] = apiPassword;
	if (DEBUG)
		console.log(users);
	app.use(basicAuth({
			users: users,
			challenge: true,
		}));
}

app.get("/*", handle);
app.post("/*", handle);
app.listen(apiPort, () => {
	console.log(`api listening on port ${apiPort}`)
})
